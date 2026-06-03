package com.kumouri.kmodigipresbe.integration.moletripwire;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.molevision.MoleClassification;
import com.kumouri.kmodigipresbe.integration.molevision.MoleClassificationCategory;
import com.kumouri.kmodigipresbe.integration.molevision.MoleVisionService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.project.Milestone;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.project.MilestoneService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the NMM B2 <strong>re-activity tripwire</strong> (Phase 3 — coverage-window
 * automation, plan §2 Feature B / §4 B2). An existing coverage customer follows a per-customer
 * tokenized link Rob texted them, uploads a photo of suspected new mole activity, and a
 * high-confidence mole <strong>auto-creates a re-treatment {@code Milestone}</strong> on their
 * {@code Project} + notifies Rob.
 *
 * <h2>A thin caller on the Phase-2 spine — REUSE, don't reinvent</h2>
 * The store → classify → threshold half of the pipeline is the <strong>Phase-2
 * {@code MoleTriageService} pipeline reused verbatim in shape</strong>: store the photo
 * ({@code FileStorageService.putBytes} + {@code Attachment(subjectType="MOLE_PHOTO")}), classify via
 * the <strong>UNCHANGED</strong> {@link MoleVisionService} (best-effort {@code onErrorResume →
 * UNSURE}), threshold on {@code kmosf.mole-tripwire.confidence-threshold}. The one structural
 * difference from Phase 2 is the routing of an above-threshold pest: instead of find-or-create a
 * fresh lead Contact, it loads the customer's existing {@code Project} (from the token's project id,
 * tenant-scoped) and creates a re-treatment {@code Milestone} via the <strong>UNCHANGED</strong>
 * {@link MilestoneService#create(UUID, Milestone)}. {@code MoleVisionService}, {@code MilestoneService},
 * {@code ProjectService}, {@code FileStorageService}, {@code ActivityCrudService} all stay empty-diff;
 * this is an additive caller.
 *
 * <h2>Auth — token only, tenant + Project from the token, never the payload</h2>
 * The path token is an HMAC {@code mole-tripwire} token verified by {@link MoleTripwireTokenService}
 * (generic token rejections surface {@code 1600-1603}; a wrong {@code widgetType} surfaces
 * {@code 4013}). Both the tenant id AND the Project id come from the token's claims
 * <strong>only</strong>; every effect runs under a synthetic
 * {@code TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"))} via {@code contextWrite} so the
 * {@code TenantStampingCallback} stamps the Attachment / Activity / Milestone with the right tenant.
 * A stranger cannot point the tripwire at another tenant's Project, nor burn another tenant's AI
 * budget. If the token's Project no longer exists for the tenant → {@code 4016}.
 *
 * <h2>No idempotency ledger (deliberate — the Phase-2 precedent)</h2>
 * A per-customer photo report carries no natural client-supplied dedupe key and is intentionally
 * re-invocable (a customer may legitimately submit several photos over the window). So — exactly
 * like {@code MoleTriageService} — there is no ledger entity and no idempotency seam here, hence no
 * {@code switchIfEmpty(process)} risk. (A double-report could create two re-treatment Milestones;
 * that is the desired "every report Rob sees" behaviour — Rob confirms/closes; the CRM never
 * auto-charges.)
 *
 * <h2>AI is triage, not truth (plan §8)</h2>
 * The vision call is best-effort ({@code onErrorResume → UNSURE}): a budget {@code 1200} / upstream
 * {@code 1202} degrades to {@code UNSURE} but the photo is still stored and the report acknowledged.
 * A below-threshold / {@code UNSURE} result is recorded and returned "unclear — we'll review", with
 * <strong>no</strong> Milestone (we don't fabricate re-treatment work off a low-confidence photo).
 *
 * <h2>§9 reactive + blocking-I/O</h2>
 * The only {@code switchIfEmpty} in this package is genuine not-found (the Project load → {@code 4016},
 * and {@code MoleVisionService}'s reused key-resolution fallback). The S3 {@code putBytes} store is
 * the non-blocking {@code S3AsyncClient}; the base64 encode (inside the reused
 * {@code MoleVisionService}) runs on {@code boundedElastic}.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.mole-tripwire", name="enabled",
 * matchIfMissing=true)} — on by default; the controller carries the same gate (the Phase-1/2
 * precedent: a disabled module → endpoint not registered → 404).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "kmosf.modules.mole-tripwire", name = "enabled",
        matchIfMissing = true)
public class MoleTripwireService {

    /** The {@code Attachment.subjectType} for a tripwire photo (reuses the Phase-2 value). */
    public static final String PHOTO_SUBJECT_TYPE = "MOLE_PHOTO";
    /** The widgetType claim a tripwire token must carry. */
    public static final String WIDGET_TYPE = MoleTripwireTokenService.WIDGET_TYPE;
    /** The S3 partition (under the tenant root) tripwire photos are stored in. */
    private static final String STORAGE_PARTITION = "mole-tripwire";
    /** The IntegrationConnection provider whose config carries the per-tenant notify targets. */
    private static final String NOTIFY_PROVIDER = TwilioSmsService.PROVIDER; // "twilio"
    /** The re-treatment Milestone is dated this many days out by default. */
    private static final int RETREATMENT_DUE_DAYS = 7;

    private final MoleTripwireTokenService tokens;
    private final FileStorageService storage;
    private final AttachmentRepository attachments;
    private final MoleVisionService visionService;
    private final ProjectRepository projects;
    private final MilestoneService milestoneService;
    private final ActivityCrudService activityCrudService;
    private final IntegrationConnectionRepository connections;
    private final EmailService emailService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    private final double confidenceThreshold;
    private final String notifyFromAddress;

    public MoleTripwireService(
            MoleTripwireTokenService tokens,
            FileStorageService storage,
            AttachmentRepository attachments,
            MoleVisionService visionService,
            ProjectRepository projects,
            MilestoneService milestoneService,
            ActivityCrudService activityCrudService,
            IntegrationConnectionRepository connections,
            EmailService emailService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.mole-tripwire.confidence-threshold:0.6}") double confidenceThreshold,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        this.tokens = tokens;
        this.storage = storage;
        this.attachments = attachments;
        this.visionService = visionService;
        this.projects = projects;
        this.milestoneService = milestoneService;
        this.activityCrudService = activityCrudService;
        this.connections = connections;
        this.emailService = emailService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.confidenceThreshold = confidenceThreshold;
        this.notifyFromAddress = notifyFromAddress;
    }

    /**
     * The entry point called by {@code MoleTripwireController}. Verify the token (tenant + Project
     * from the token only), then run the store → classify → threshold → (re-treatment Milestone +
     * notify) pipeline under the synthetic tenant context.
     */
    public Mono<MoleTripwireResponse> report(String token, byte[] imageBytes, String mediaType,
                                             String filename, String note) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<MoleTripwireResponse>error(new DigiPresBeException(
                                "Tripwire token type mismatch (expected '" + WIDGET_TYPE
                                        + "', got '" + claims.widgetType() + "')",
                                4013, 401));
                    }
                    if (!MoleVisionService.isSupportedMediaType(mediaType)) {
                        return Mono.<MoleTripwireResponse>error(new DigiPresBeException(
                                "Unsupported image media type '" + mediaType
                                        + "' (accepted: image/jpeg, image/png, image/webp, image/gif)",
                                4015, 415));
                    }
                    return runPipeline(claims, imageBytes, mediaType, filename, note);
                });
    }

    private Mono<MoleTripwireResponse> runPipeline(MoleTripwireToken claims, byte[] imageBytes,
                                                   String mediaType, String filename, String note) {
        UUID tenantId = claims.tenantId();
        UUID projectId = claims.projectId();
        TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));

        Mono<MoleTripwireResponse> body = storePhoto(tenantId, imageBytes, mediaType, filename)
                .flatMap(attachment -> classify(imageBytes, mediaType, attachment)
                        .flatMap(classification -> {
                            boolean above = isAboveThreshold(classification);
                            emitTripwireReported(tenantId, projectId, classification, attachment, above);
                            if (above) {
                                return createRetreatmentAndNotify(
                                        tenantId, projectId, classification, attachment, note)
                                        .map(milestoneId -> buildResponse(
                                                classification, true, attachment.getId(), milestoneId));
                            }
                            // Below-threshold / UNSURE: record + notify, but NO Milestone.
                            return notifyRob(projectId, classification, attachment, note, null)
                                    .thenReturn(buildResponse(
                                            classification, false, attachment.getId(), null));
                        }));

        return body.contextWrite(TenantContextHolder.write(anon));
    }

    /**
     * Stores the raw photo bytes in S3 ({@code FileStorageService.putBytes} — non-blocking
     * {@code S3AsyncClient}) and registers an {@code Attachment(subjectType=MOLE_PHOTO)}
     * (auto-tenant-stamped under the synthetic context). Identical to the Phase-2 store step.
     */
    private Mono<Attachment> storePhoto(UUID tenantId, byte[] imageBytes, String mediaType,
                                        String filename) {
        String suffix = suffixFor(mediaType, filename);
        return storage.putBytes(tenantId, STORAGE_PARTITION, imageBytes, mediaType, suffix)
                .flatMap(storageRef -> attachments.save(Attachment.builder()
                        .subjectType(PHOTO_SUBJECT_TYPE)
                        .filename(filename != null && !filename.isBlank() ? filename : "photo." + suffix)
                        .contentType(mediaType)
                        .sizeBytes((long) imageBytes.length)
                        .storageRef(storageRef)
                        .build()));
    }

    /**
     * Best-effort vision classify via the UNCHANGED {@link MoleVisionService}: an AI budget/upstream
     * failure must NOT drop the report (plan §8), so {@code onErrorResume} degrades to
     * {@link MoleClassification#unsure()}.
     */
    private Mono<MoleClassification> classify(byte[] imageBytes, String mediaType,
                                              Attachment attachment) {
        return visionService.classify(imageBytes, mediaType)
                .onErrorResume(e -> {
                    log.warn("Mole-tripwire classify failed for attachment {} (best-effort, using "
                            + "UNSURE): {}", attachment.getId(), e.getMessage());
                    return Mono.just(MoleClassification.unsure());
                });
    }

    private boolean isAboveThreshold(MoleClassification classification) {
        return classification.isPest() && classification.confidence() >= confidenceThreshold;
    }

    /**
     * Above-threshold path: load the customer's {@code Project} (tenant-scoped, from the token's
     * project id — {@code 4016} if absent), create a re-treatment {@code Milestone} via the
     * <strong>UNCHANGED</strong> {@link MilestoneService#create(UUID, Milestone)}, log an
     * {@code Activity(NOTE)} against the Project's primary Contact, best-effort notify Rob, and
     * publish {@code RETREATMENT_MILESTONE_CREATED}. Returns the new milestone id.
     */
    private Mono<UUID> createRetreatmentAndNotify(UUID tenantId, UUID projectId,
                                                  MoleClassification classification,
                                                  Attachment attachment, String note) {
        return projects.findByTenantIdAndId(tenantId, projectId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Tripwire token references a Project that no longer exists", 4016, 404)))
                .flatMap(project -> {
                    Milestone retreatment = Milestone.builder()
                            .name("Re-treatment — suspected new mole activity")
                            .status(Milestone.MilestoneStatus.PENDING)
                            .dueDate(LocalDate.now().plusDays(RETREATMENT_DUE_DAYS))
                            .build();
                    // UNCHANGED MilestoneService.create — stamps tenant from the synthetic context,
                    // validates the name, sets PENDING + spawnedInvoiceId=null.
                    return milestoneService.create(projectId, retreatment)
                            .flatMap(created -> logTripwireActivity(
                                    tenantId, project, created, classification, attachment, note)
                                    .flatMap(activity -> notifyRob(
                                            projectId, classification, attachment, note, created.getId())
                                            .then(Mono.fromRunnable(() -> emitRetreatmentCreated(
                                                    tenantId, projectId, created, classification, attachment)))
                                            .thenReturn(created.getId())));
                });
    }

    /**
     * Logs the tripwire report via the UNCHANGED {@code ActivityCrudService.create}: type=NOTE,
     * subjectType=CONTACT (the Project's {@code primaryContactId} — {@code SubjectType} has no
     * PROJECT value and is left untouched; the payload carries {@code projectId}/{@code milestoneId}
     * for the linkage), payload = {classification, confidence, attachmentId, projectId,
     * milestoneId?, note?}.
     */
    private Mono<Activity> logTripwireActivity(UUID tenantId, Project project, Milestone milestone,
                                               MoleClassification classification, Attachment attachment,
                                               String note) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classification", classification.category().wire());
        payload.put("confidence", classification.confidence());
        payload.put("aboveThreshold", isAboveThreshold(classification));
        payload.put("projectId", project.getId().toString());
        if (milestone != null && milestone.getId() != null) {
            payload.put("milestoneId", milestone.getId().toString());
        }
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        if (classification.rationale() != null) payload.put("rationale", classification.rationale());
        if (note != null) payload.put("note", note);

        StringBuilder bodyText = new StringBuilder("Coverage tripwire report — ")
                .append(classification.toSummaryLine());
        if (milestone != null) {
            bodyText.append("\nRe-treatment milestone created (due ")
                    .append(milestone.getDueDate()).append(").");
        }
        if (note != null) {
            bodyText.append("\nCustomer note: ").append(note);
        }

        Activity activity = Activity.builder()
                .tenantId(tenantId)
                .type(ActivityType.NOTE)
                .subjectType(SubjectType.CONTACT)
                .subjectId(project.getPrimaryContactId())
                .summary("Coverage tripwire: " + classification.toSummaryLine())
                .body(bodyText.toString())
                .payload(payload)
                .build();
        return activityCrudService.create(activity);
    }

    /**
     * Best-effort notify Rob — email + SMS — to the per-tenant targets in the Twilio
     * {@code IntegrationConnection.config} ({@code notifyEmail} / {@code notifyPhone}); NOT
     * hardcoded. A missing connection / missing target / send failure is swallowed
     * ({@code onErrorResume}) so the already-durable Milestone/Activity are never lost.
     */
    private Mono<Void> notifyRob(UUID projectId, MoleClassification classification,
                                 Attachment attachment, String note, UUID retreatmentMilestoneId) {
        return TenantContextHolder.required()
                .flatMap(ctx -> connections.findByTenantIdAndProvider(ctx.tenantId(), NOTIFY_PROVIDER))
                .flatMap(conn -> dispatchNotify(conn, projectId, classification, note, retreatmentMilestoneId))
                .onErrorResume(e -> {
                    log.warn("Mole-tripwire notify failed (best-effort, ignored): {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> dispatchNotify(IntegrationConnection conn, UUID projectId,
                                      MoleClassification classification, String note,
                                      UUID retreatmentMilestoneId) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");
        String summary = classification.toSummaryLine();
        String headline = retreatmentMilestoneId != null
                ? "Coverage customer reported new mole activity — re-treatment milestone created."
                : "Coverage customer submitted a tripwire photo (unclear — needs review).";

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            StringBuilder html = new StringBuilder();
            html.append("<p>").append(HtmlUtils.htmlEscape(headline)).append("</p>");
            html.append("<p>").append(HtmlUtils.htmlEscape(summary)).append("</p>");
            html.append("<p>Project: ").append(HtmlUtils.htmlEscape(projectId.toString())).append("</p>");
            if (retreatmentMilestoneId != null) {
                html.append("<p>Re-treatment milestone: ")
                        .append(HtmlUtils.htmlEscape(retreatmentMilestoneId.toString())).append("</p>");
            }
            if (note != null) {
                html.append("<p>Customer note: ").append(HtmlUtils.htmlEscape(note)).append("</p>");
            }
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject("Coverage tripwire — " + classification.category().wire())
                    .body(html.toString())
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("Mole-tripwire notify-email failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }

        Mono<Void> smsMono = Mono.empty();
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            String smsBody = headline + " " + summary;
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(smsBody)
                    .build();
            smsMono = twilioSmsService.sendSms(smsReq)
                    .onErrorResume(e -> {
                        log.warn("Mole-tripwire notify-SMS failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    private MoleTripwireResponse buildResponse(MoleClassification classification, boolean above,
                                               UUID attachmentId, UUID retreatmentMilestoneId) {
        String message;
        if (above) {
            message = "Thanks — that looks like a " + classification.category().wire()
                    + ". We've flagged a re-treatment and Rob will follow up.";
        } else if (classification.category() == MoleClassificationCategory.NONE) {
            message = "Thanks for the photo — we didn't spot a clear pest sign, but Rob can take a "
                    + "closer look if you're concerned.";
        } else {
            message = "Thanks for the photo — this one's unclear from the image, so we'll review it "
                    + "and Rob will follow up if needed.";
        }
        return new MoleTripwireResponse(
                classification.category().wire(),
                classification.confidence(),
                above,
                message,
                attachmentId,
                retreatmentMilestoneId);
    }

    private void emitTripwireReported(UUID tenantId, UUID projectId, MoleClassification classification,
                                      Attachment attachment, boolean above) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classification", classification.category().wire());
        payload.put("confidence", classification.confidence());
        payload.put("aboveThreshold", above);
        payload.put("projectId", projectId.toString());
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.MOLE_TRIPWIRE_REPORTED, tenantId,
                attachment.getId() != null ? attachment.getId() : UUID.randomUUID(), payload));
    }

    private void emitRetreatmentCreated(UUID tenantId, UUID projectId, Milestone milestone,
                                        MoleClassification classification, Attachment attachment) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classification", classification.category().wire());
        payload.put("confidence", classification.confidence());
        payload.put("projectId", projectId.toString());
        if (milestone.getId() != null) payload.put("milestoneId", milestone.getId().toString());
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.RETREATMENT_MILESTONE_CREATED, tenantId,
                milestone.getId() != null ? milestone.getId() : UUID.randomUUID(), payload));
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
