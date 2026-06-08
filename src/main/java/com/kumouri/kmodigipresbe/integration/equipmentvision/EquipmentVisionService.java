package com.kumouri.kmodigipresbe.integration.equipmentvision;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the Home Services <strong>equipment-nameplate photo</strong> enrichment (HS-2 —
 * "Front Desk That Never Sleeps"). After a home-services voicemail creates a DRAFT {@code WorkOrder}
 * (HS-1), the caller follows the tokenized upload link the HS-1 auto-ack SMS carried and uploads a
 * photo of the equipment; this service stores the photo, reads the nameplate with vision, and
 * enriches <em>their</em> DRAFT WorkOrder + logs an Activity + enriches the owner digest.
 *
 * <h2>The {@code extract}-shaped, WorkOrder-targeted twin of {@code MoleTripwireService}</h2>
 * The store → AI → enrich → notify pipeline is the Phase-3 {@code MoleTripwireService} pipeline
 * reused in shape: store the photo ({@code FileStorageService.putBytes} +
 * {@code Attachment(subjectType="WORK_ORDER")}), call the <strong>UNCHANGED</strong> shared
 * {@link AiVisionService#extract} for an open-schema nameplate read (best-effort), load the bound
 * entity tenant-scoped from the token claim, and enrich it. The one difference from the tripwire is
 * the AI call shape ({@code extract} → raw JSON nameplate fields, vs the tripwire's
 * {@code classify} → category/confidence) and the bound entity (a DRAFT {@code WorkOrder}, vs a
 * coverage {@code Project}). {@code AiVisionService}, {@code FileStorageService},
 * {@code ActivityCrudService}, {@code TwilioSmsService}, {@code EmailService} all stay empty-diff;
 * this is an additive caller.
 *
 * <h2>Auth — token only, tenant + WorkOrder from the token, never the payload</h2>
 * The path token is an HMAC {@code equipment-photo} token verified by
 * {@link EquipmentPhotoTokenService} (generic token rejections surface {@code 1600-1603}; a wrong
 * {@code widgetType} surfaces {@code 4210}). Both the tenant id AND the WorkOrder id come from the
 * token's claims <strong>only</strong>; every effect runs under a synthetic
 * {@code TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"))} via {@code contextWrite} so the
 * {@code TenantStampingCallback} stamps the Attachment / Activity with the right tenant. A stranger
 * cannot point an upload at another tenant's WorkOrder, nor burn another tenant's AI budget. If the
 * token's WorkOrder no longer exists for the tenant → {@code 4212}.
 *
 * <h2>No idempotency ledger, no inbound-MMS webhook (deliberate — plan §3 design fork)</h2>
 * The photo arrives over a tokenized HTTPS upload (the proven {@code MoleTriageController} /
 * {@code MoleTripwireController} pattern), <strong>not</strong> an inbound Twilio MMS webhook — so
 * HS-2 adds <em>no</em> new 10DLC surface and no new inbound-SMS handler. Like the tripwire, a
 * per-caller photo upload carries no natural dedupe key and is intentionally re-invocable (a caller
 * may send several photos), so there is no ledger entity and no {@code switchIfEmpty(process)} risk.
 *
 * <h2>AI is triage, not truth (plan §8)</h2>
 * The vision call is best-effort: a budget {@code 1200} / upstream {@code 1202} / parse failure
 * degrades — via {@link AiVisionService#extract}'s own empty-object fallback PLUS an
 * {@code onErrorResume} here — to an empty {@link EquipmentReading}, but the photo is still stored,
 * the upload is still acknowledged, and the WorkOrder is simply left un-enriched (never errored,
 * never dropped). A read that finds nothing legible is the same: 200, {@code enriched=false}.
 *
 * <h2>§9 reactive + blocking-I/O</h2>
 * The only {@code switchIfEmpty} in this package is genuine not-found (the WorkOrder load →
 * {@code 4212}). The S3 {@code putBytes} store is the non-blocking {@code S3AsyncClient}; the base64
 * encode (inside the reused {@code AiVisionService}) runs on {@code boundedElastic}.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.home-services", name="enabled")} — strict
 * opt-in (NOT {@code matchIfMissing}); the controller + token issuer carry the same gate (the HS-1
 * {@code MissedCallInboxController} precedent: a disabled module → bean absent → endpoint not
 * registered → 404). The nameplate read only makes sense for a home-services tenant whose voicemail
 * created a DRAFT WorkOrder in the first place.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
public class EquipmentVisionService {

    /** The {@code Attachment.subjectType} for an equipment-nameplate photo (bound to the WorkOrder). */
    public static final String PHOTO_SUBJECT_TYPE = "WORK_ORDER";
    /** The widgetType claim an equipment-photo token must carry. */
    public static final String WIDGET_TYPE = EquipmentPhotoTokenService.WIDGET_TYPE;
    /** The S3 partition (under the tenant root) equipment photos are stored in. */
    private static final String STORAGE_PARTITION = "equipment-photos";
    /** The IntegrationConnection provider whose config carries the per-tenant notify targets. */
    private static final String NOTIFY_PROVIDER = TwilioSmsService.PROVIDER; // "twilio"

    private static final String SYSTEM_PROMPT =
            "You read the nameplate / data plate of a piece of home-services equipment (an HVAC "
            + "furnace or condenser, a water heater, an electrical panel, a pump, etc.) from a photo. "
            + "Respond with ONLY a single minified JSON object and nothing else — no prose, no "
            + "markdown, no code fences. The object MUST have exactly these keys: \"make\" (the "
            + "manufacturer/brand printed on the plate, or null), \"model\" (the model number or "
            + "name, or null), \"serial\" (the serial number, or null), \"equipmentType\" (what the "
            + "unit is, e.g. \"furnace\", \"condenser\", \"water heater\", \"electrical panel\", or "
            + "null), and \"observedSymptom\" (any visible fault or condition you can see in the "
            + "photo, e.g. \"rust on heat exchanger\", \"tripped breaker\", \"corroded fitting\", or "
            + "null). Transcribe exactly what is printed; use null for any field you cannot read. Do "
            + "not invent values; this is a triage hint a human will confirm.";

    private static final String USER_TEXT =
            "Read this equipment nameplate and return the make, model, serial, equipmentType, and any "
            + "observedSymptom as the specified JSON.";

    private final EquipmentPhotoTokenService tokens;
    private final FileStorageService storage;
    private final AttachmentRepository attachments;
    private final AiVisionService visionService;
    private final ObjectMapper objectMapper;
    private final WorkOrderRepository workOrders;
    private final ActivityCrudService activityCrudService;
    private final IntegrationConnectionRepository connections;
    private final EmailService emailService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    private final String visionModel;
    private final String notifyFromAddress;

    public EquipmentVisionService(
            EquipmentPhotoTokenService tokens,
            FileStorageService storage,
            AttachmentRepository attachments,
            AiVisionService visionService,
            ObjectMapper objectMapper,
            WorkOrderRepository workOrders,
            ActivityCrudService activityCrudService,
            IntegrationConnectionRepository connections,
            EmailService emailService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.home-services.equipment-vision-model:claude-sonnet-4-5}") String visionModel,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        this.tokens = tokens;
        this.storage = storage;
        this.attachments = attachments;
        this.visionService = visionService;
        this.objectMapper = objectMapper;
        this.workOrders = workOrders;
        this.activityCrudService = activityCrudService;
        this.connections = connections;
        this.emailService = emailService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.visionModel = visionModel;
        this.notifyFromAddress = notifyFromAddress;
    }

    /**
     * The entry point called by {@code EquipmentPhotoController}. Verify the token (tenant +
     * WorkOrder from the token only), then run the store → read → enrich → notify pipeline under the
     * synthetic tenant context.
     */
    public Mono<EquipmentPhotoResponse> submit(String token, byte[] imageBytes, String mediaType,
                                               String filename, String note) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<EquipmentPhotoResponse>error(new DigiPresBeException(
                                "Equipment-photo token type mismatch (expected '" + WIDGET_TYPE
                                        + "', got '" + claims.widgetType() + "')",
                                4210, 401));
                    }
                    if (!AiVisionService.isSupportedMediaType(mediaType)) {
                        return Mono.<EquipmentPhotoResponse>error(new DigiPresBeException(
                                "Unsupported image media type '" + mediaType
                                        + "' (accepted: image/jpeg, image/png, image/webp, image/gif)",
                                4211, 415));
                    }
                    return runPipeline(claims, imageBytes, mediaType, filename, note);
                });
    }

    private Mono<EquipmentPhotoResponse> runPipeline(EquipmentPhotoToken claims, byte[] imageBytes,
                                                     String mediaType, String filename, String note) {
        UUID tenantId = claims.tenantId();
        UUID workOrderId = claims.workOrderId();
        TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));

        Mono<EquipmentPhotoResponse> body = storePhoto(tenantId, workOrderId, imageBytes, mediaType,
                filename)
                .flatMap(attachment -> readNameplate(imageBytes, mediaType, attachment)
                        .flatMap(reading -> enrichAndNotify(
                                tenantId, workOrderId, reading, attachment, note)
                                .thenReturn(buildResponse(reading, attachment.getId()))));

        return body.contextWrite(TenantContextHolder.write(anon));
    }

    /**
     * Stores the raw photo bytes in S3 ({@code FileStorageService.putBytes} — non-blocking
     * {@code S3AsyncClient}) and registers an {@code Attachment(subjectType="WORK_ORDER",
     * subjectId=workOrderId)} (auto-tenant-stamped under the synthetic context). Binding the
     * Attachment to the WorkOrder means HS-4's inbox card can list the photo by WorkOrder.
     * Storage failures surface {@code 1310} from {@code FileStorageService}.
     */
    private Mono<Attachment> storePhoto(UUID tenantId, UUID workOrderId, byte[] imageBytes,
                                        String mediaType, String filename) {
        String suffix = suffixFor(mediaType, filename);
        return storage.putBytes(tenantId, STORAGE_PARTITION, imageBytes, mediaType, suffix)
                .flatMap(storageRef -> attachments.save(Attachment.builder()
                        .subjectType(PHOTO_SUBJECT_TYPE)
                        .subjectId(workOrderId)
                        .filename(filename != null && !filename.isBlank() ? filename : "equipment." + suffix)
                        .contentType(mediaType)
                        .sizeBytes((long) imageBytes.length)
                        .storageRef(storageRef)
                        .build()));
    }

    /**
     * Best-effort nameplate read via the UNCHANGED shared {@link AiVisionService#extract} (open-schema
     * JSON): an AI budget/upstream/parse failure must NOT drop the lead (plan §8). {@code extract}
     * already degrades to an empty {@code ObjectNode} internally; the {@code onErrorResume} here is a
     * belt-and-suspenders for an upstream-thrown error (budget {@code 1200}, missing-key {@code 1203})
     * that short-circuits before that internal fallback — either way we end with an empty
     * {@link EquipmentReading}.
     */
    private Mono<EquipmentReading> readNameplate(byte[] imageBytes, String mediaType,
                                                 Attachment attachment) {
        return visionService.extract(imageBytes, mediaType, visionModel, SYSTEM_PROMPT, USER_TEXT)
                .map(EquipmentReading::fromJson)
                .onErrorResume(e -> {
                    log.warn("Equipment-vision read failed for attachment {} (best-effort, no "
                            + "enrichment): {}", attachment.getId(), e.getMessage());
                    return Mono.just(EquipmentReading.fromJson(objectMapper.createObjectNode()));
                });
    }

    /**
     * Loads the bound DRAFT {@link WorkOrder} (tenant-scoped, from the token's WorkOrder id —
     * {@code 4212} if absent), enriches its {@code notes} + {@code customFields} with the legible
     * nameplate fields, logs an {@code Activity(NOTE, subjectType=WORK_ORDER)}, and best-effort
     * notifies the owner digest. A blank read enriches nothing (no WorkOrder write, no Activity) but
     * still publishes the advisory event and acknowledges the upload.
     */
    private Mono<Void> enrichAndNotify(UUID tenantId, UUID workOrderId, EquipmentReading reading,
                                       Attachment attachment, String note) {
        return workOrders.findById(workOrderId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Equipment-photo token references a WorkOrder that no longer exists",
                        4212, 404)))
                .flatMap(wo -> {
                    emitEquipmentRead(tenantId, workOrderId, reading, attachment);
                    if (reading.isEmpty()) {
                        // Nothing legible — still notify (so the owner knows a photo came in), but
                        // make no WorkOrder/Activity mutation.
                        return notifyOwner(workOrderId, reading, note)
                                .then();
                    }
                    return applyReadingToWorkOrder(wo, reading, attachment)
                            .flatMap(saved -> logEquipmentActivity(
                                    tenantId, saved, reading, attachment, note)
                                    .then(notifyOwner(workOrderId, reading, note)));
                })
                .then();
    }

    /**
     * Writes the legible nameplate fields onto the WorkOrder: each non-blank field into
     * {@code customFields} (merged over the existing map, insertion-ordered) and a human-readable
     * "Equipment (from photo): ..." line appended to {@code notes}. Returns the saved WorkOrder.
     */
    private Mono<WorkOrder> applyReadingToWorkOrder(WorkOrder wo, EquipmentReading reading,
                                                    Attachment attachment) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (wo.getCustomFields() != null) merged.putAll(wo.getCustomFields());
        merged.putAll(reading.toFieldMap());
        if (attachment.getId() != null) {
            merged.put("equipmentPhotoAttachmentId", attachment.getId().toString());
        }
        wo.setCustomFields(merged);

        String existingNotes = wo.getNotes() == null ? "" : wo.getNotes();
        String enrichmentLine = "\n\nEquipment (from photo): " + reading.toSummaryLine();
        wo.setNotes(existingNotes + enrichmentLine);

        return workOrders.save(wo);
    }

    /**
     * Logs the nameplate read via the UNCHANGED {@code ActivityCrudService.create}: type=NOTE,
     * subjectType=WORK_ORDER (the {@code SubjectType.WORK_ORDER} the model already has), payload =
     * {make, model, serial, equipmentType, observedSymptom, attachmentId, workOrderId, note?}.
     */
    private Mono<Activity> logEquipmentActivity(UUID tenantId, WorkOrder wo, EquipmentReading reading,
                                                Attachment attachment, String note) {
        Map<String, Object> payload = new LinkedHashMap<>(reading.toFieldMap());
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        if (wo.getId() != null) payload.put("workOrderId", wo.getId().toString());
        if (note != null && !note.isBlank()) payload.put("note", note);

        StringBuilder bodyText = new StringBuilder("Equipment photo read from the caller: ")
                .append(reading.toSummaryLine());
        if (note != null && !note.isBlank()) {
            bodyText.append("\nCaller note: ").append(note);
        }

        Activity activity = Activity.builder()
                .tenantId(tenantId)
                .type(ActivityType.NOTE)
                .subjectType(SubjectType.WORK_ORDER)
                .subjectId(wo.getId())
                .summary("Equipment nameplate: " + reading.toSummaryLine())
                .body(bodyText.toString())
                .payload(payload)
                .build();
        return activityCrudService.create(activity);
    }

    /**
     * Best-effort enrich the owner digest — email + SMS — to the per-tenant targets in the Twilio
     * {@code IntegrationConnection.config} ({@code notifyEmail} / {@code notifyPhone}); NOT
     * hardcoded. A missing connection / missing target / send failure is swallowed
     * ({@code onErrorResume}) so the already-durable WorkOrder enrichment is never lost.
     */
    private Mono<Void> notifyOwner(UUID workOrderId, EquipmentReading reading, String note) {
        return TenantContextHolder.required()
                .flatMap(ctx -> connections.findByTenantIdAndProvider(ctx.tenantId(), NOTIFY_PROVIDER))
                .flatMap(conn -> dispatchNotify(conn, workOrderId, reading, note))
                .onErrorResume(e -> {
                    log.warn("Equipment-vision notify failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> dispatchNotify(IntegrationConnection conn, UUID workOrderId,
                                      EquipmentReading reading, String note) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");
        String summary = reading.toSummaryLine();
        String headline = reading.isEmpty()
                ? "Caller uploaded an equipment photo (no legible nameplate — see the photo)."
                : "Caller uploaded an equipment photo — nameplate read: " + summary;

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            StringBuilder html = new StringBuilder();
            html.append("<p>").append(HtmlUtils.htmlEscape(headline)).append("</p>");
            html.append("<p>Work order: ").append(HtmlUtils.htmlEscape(workOrderId.toString()))
                    .append("</p>");
            if (note != null && !note.isBlank()) {
                html.append("<p>Caller note: ").append(HtmlUtils.htmlEscape(note)).append("</p>");
            }
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject("Equipment photo for work order " + workOrderId)
                    .body(html.toString())
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("Equipment-vision notify-email failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }

        Mono<Void> smsMono = Mono.empty();
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(headline)
                    .build();
            smsMono = twilioSmsService.sendSms(smsReq)
                    .onErrorResume(e -> {
                        log.warn("Equipment-vision notify-SMS failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    private EquipmentPhotoResponse buildResponse(EquipmentReading reading, UUID attachmentId) {
        String message;
        if (reading.isEmpty()) {
            message = "Thanks — we've got your photo. We couldn't read a nameplate from it, but it's "
                    + "attached to your job and the technician will take a look.";
        } else {
            message = "Thanks — we've got your photo and the equipment details. The technician will "
                    + "have them before the visit.";
        }
        return new EquipmentPhotoResponse(
                !reading.isEmpty(),
                reading.make(),
                reading.model(),
                reading.serial(),
                message,
                attachmentId);
    }

    private void emitEquipmentRead(UUID tenantId, UUID workOrderId, EquipmentReading reading,
                                   Attachment attachment) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("workOrderId", workOrderId.toString());
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        payload.put("enriched", !reading.isEmpty());
        payload.putAll(reading.toFieldMap());
        events.publish(DomainEvent.of(
                DomainEventType.EQUIPMENT_PHOTO_READ, tenantId, workOrderId, payload));
    }

    private static String suffixFor(String mediaType, String filename) {
        if (filename != null && filename.contains(".")) {
            String ext = filename.substring(filename.lastIndexOf('.') + 1).trim();
            if (!ext.isEmpty() && ext.length() <= 5) return ext.toLowerCase();
        }
        if (mediaType == null) return "jpg";
        return switch (mediaType.trim().toLowerCase()) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            case "image/gif" -> "gif";
            default -> "jpg";
        };
    }
}
