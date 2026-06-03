package com.kumouri.kmodigipresbe.integration.molevision;

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
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates the NMM "is this a mole?" photo-triage pipeline (Phase 2 — Feature B). The photo
 * channel is a <strong>thin front end on the same shared intake/notify spine that Phase 1
 * (voicemail-to-lead) stood up</strong>: store the inbound signal, classify it, find-or-create a
 * lead Contact, log an Activity, notify Rob, and return the result. The one net-new piece is the
 * {@link MoleVisionService} call; everything else reuses the existing storage / Contact / Activity
 * / notify cores (all empty-diff vs {@code main}).
 *
 * <h2>Auth — token only, tenant from the token, never the payload</h2>
 * The path token is an HMAC widget token verified by {@link PublicWidgetTokenService#verify}
 * (the {@code ServiceRequestWidgetController} precedent — token rejections surface
 * {@code 1600-1603}); a wrong {@code widgetType} surfaces {@code 4010}. The tenant id comes from
 * the token's claim <strong>only</strong>; every effect runs under a synthetic
 * {@code TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"))} via {@code contextWrite} so the
 * {@code TenantStampingCallback} stamps the Attachment / Contact / Activity with the right tenant.
 *
 * <h2>No idempotency ledger (deliberate)</h2>
 * Unlike the Phase-1 voicemail pipeline (which dedupes on the Twilio {@code CallSid}), a public
 * photo classify carries no natural client-supplied dedupe key and is <strong>intentionally
 * re-invocable</strong> — the same homeowner may legitimately submit several photos. This mirrors
 * the {@code ServiceRequestWidgetController} (which also has no idempotency ledger). There is
 * therefore no ledger entity and no idempotency seam in this package — and so no
 * {@code switchIfEmpty(process)} risk.
 *
 * <h2>AI is triage, not truth (plan §8)</h2>
 * The vision call is <strong>best-effort</strong> ({@code onErrorResume → UNSURE}): a budget
 * {@code 1200} / upstream {@code 1202} degrades to an {@code UNSURE} classification but the photo
 * is still stored, the lead is still created, and Rob is still notified. A below-threshold or
 * {@code UNSURE} result is recorded and returned framed as "possible/unclear — a human will
 * confirm". The CRM never auto-charges; Rob always reviews.
 *
 * <h2>§9 reactive + blocking-I/O</h2>
 * Contact find-or-create is an <strong>explicit-boolean branch</strong> (never
 * {@code switchIfEmpty(create)}); the {@code switchIfEmpty} in this package is only
 * {@code MoleVisionService}'s genuine key-resolution house-key fallback (the verbatim
 * {@code AnthropicAiAssistService} pattern). The S3 {@code putBytes} store + the base64 encode
 * (inside {@code MoleVisionService}) are the only blocking/CPU-bound steps and run off the Netty
 * loop ({@code S3AsyncClient} is non-blocking; the base64 is on {@code boundedElastic}).
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.mole-triage", name="enabled",
 * matchIfMissing=true)} — on by default; the controller carries the same gate (the Phase-1
 * voicemail-controller precedent: a disabled module → endpoint not registered → 404).
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "kmosf.modules.mole-triage", name = "enabled",
        matchIfMissing = true)
public class MoleTriageService {

    /** The {@code Attachment.subjectType} for a triaged homeowner photo (free-form String). */
    public static final String PHOTO_SUBJECT_TYPE = "MOLE_PHOTO";
    /** The widgetType claim a mole-triage token must carry. */
    public static final String WIDGET_TYPE = "mole-triage";
    /** The S3 partition (under the tenant root) the photos are stored in. */
    private static final String STORAGE_PARTITION = "mole-triage";
    /** The IntegrationConnection provider whose config carries the per-tenant notify targets. */
    private static final String NOTIFY_PROVIDER = TwilioSmsService.PROVIDER; // "twilio"

    private final PublicWidgetTokenService tokens;
    private final FileStorageService storage;
    private final AttachmentRepository attachments;
    private final MoleVisionService visionService;
    private final ContactRepository contacts;
    private final ActivityCrudService activityCrudService;
    private final IntegrationConnectionRepository connections;
    private final EmailService emailService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    private final double confidenceThreshold;
    private final String notifyFromAddress;

    public MoleTriageService(
            PublicWidgetTokenService tokens,
            FileStorageService storage,
            AttachmentRepository attachments,
            MoleVisionService visionService,
            ContactRepository contacts,
            ActivityCrudService activityCrudService,
            IntegrationConnectionRepository connections,
            EmailService emailService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.mole-triage.confidence-threshold:0.6}") double confidenceThreshold,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        this.tokens = tokens;
        this.storage = storage;
        this.attachments = attachments;
        this.visionService = visionService;
        this.contacts = contacts;
        this.activityCrudService = activityCrudService;
        this.connections = connections;
        this.emailService = emailService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.confidenceThreshold = confidenceThreshold;
        this.notifyFromAddress = notifyFromAddress;
    }

    /**
     * The entry point called by {@code MoleTriageController}. Verify the token (tenant from the
     * token only), then run the store → classify → threshold → lead → notify pipeline under the
     * synthetic tenant context.
     */
    public Mono<MoleTriageResponse> triage(String token, byte[] imageBytes, String mediaType,
                                           String filename, MolePhotoIntakeParams intake) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<MoleTriageResponse>error(new DigiPresBeException(
                                "Widget token type mismatch (expected '" + WIDGET_TYPE
                                        + "', got '" + claims.widgetType() + "')",
                                4010, 401));
                    }
                    if (!MoleVisionService.isSupportedMediaType(mediaType)) {
                        return Mono.<MoleTriageResponse>error(new DigiPresBeException(
                                "Unsupported image media type '" + mediaType
                                        + "' (accepted: image/jpeg, image/png, image/webp, image/gif)",
                                4012, 415));
                    }
                    return runPipeline(claims, imageBytes, mediaType, filename, intake);
                });
    }

    private Mono<MoleTriageResponse> runPipeline(PublicWidgetToken claims, byte[] imageBytes,
                                                 String mediaType, String filename,
                                                 MolePhotoIntakeParams intake) {
        UUID tenantId = claims.tenantId();
        TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));

        Mono<MoleTriageResponse> body = storePhoto(tenantId, imageBytes, mediaType, filename)
                .flatMap(attachment -> classify(imageBytes, mediaType, attachment)
                        .flatMap(classification -> {
                            boolean above = isAboveThreshold(classification);
                            emitPhotoClassified(tenantId, classification, attachment, above);
                            return createLeadAndNotify(tenantId, classification, attachment, intake)
                                    .thenReturn(buildResponse(classification, above, attachment.getId()));
                        }));

        return body.contextWrite(TenantContextHolder.write(anon));
    }

    /**
     * Stores the raw photo bytes in S3 ({@code FileStorageService.putBytes} — the
     * {@code S3AsyncClient}, non-blocking) and registers an {@code Attachment(subjectType=
     * MOLE_PHOTO)} via the {@code AttachmentRepository} (auto-tenant-stamped under the synthetic
     * context). Storage failures surface {@code 1310}/{@code 1311} from {@code FileStorageService}.
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
     * Best-effort vision classify: an AI budget/upstream failure must NOT drop the lead (plan §8),
     * so {@code onErrorResume} degrades to {@link MoleClassification#unsure()}.
     */
    private Mono<MoleClassification> classify(byte[] imageBytes, String mediaType,
                                              Attachment attachment) {
        return visionService.classify(imageBytes, mediaType)
                .onErrorResume(e -> {
                    log.warn("Mole-triage classify failed for attachment {} (best-effort, using "
                            + "UNSURE): {}", attachment.getId(), e.getMessage());
                    return Mono.just(MoleClassification.unsure());
                });
    }

    private boolean isAboveThreshold(MoleClassification classification) {
        return classification.isPest() && classification.confidence() >= confidenceThreshold;
    }

    /**
     * Find-or-create the lead Contact → log an {@code Activity(NOTE, subjectType=CONTACT)} via the
     * <strong>unchanged</strong> {@code ActivityCrudService.create} → best-effort notify Rob →
     * publish {@code MOLE_LEAD_CREATED}.
     */
    private Mono<Void> createLeadAndNotify(UUID tenantId, MoleClassification classification,
                                           Attachment attachment, MolePhotoIntakeParams intake) {
        return findOrCreateContact(intake)
                .flatMap(contact -> logTriageActivity(tenantId, contact, classification, attachment, intake)
                        .flatMap(activity -> notifyRob(classification, attachment, intake)
                                .then(Mono.fromRunnable(() -> emitLeadCreated(
                                        tenantId, classification, attachment, contact, activity)))))
                .then();
    }

    /**
     * Find-or-create the lead Contact, <strong>explicit-boolean</strong> (never
     * {@code switchIfEmpty(create)}). Keyed on the supplied phone, then email; an existing contact
     * is returned untouched (we don't rewrite staff-curated data from a public submission — the
     * {@code ServiceRequestWidgetController} precedent). With neither phone nor email, an anonymous
     * contact is created so the lead is never dropped.
     */
    private Mono<Contact> findOrCreateContact(MolePhotoIntakeParams intake) {
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID tenantId = ctx.tenantId();
            Mono<Optional<Contact>> existing;
            if (intake.phone() != null) {
                existing = contacts.findByTenantAndPhoneNumber(tenantId, intake.phone())
                        .next().map(Optional::of).defaultIfEmpty(Optional.empty());
            } else if (intake.email() != null) {
                existing = contacts.findByTenantAndEmailAddress(tenantId, intake.email())
                        .next().map(Optional::of).defaultIfEmpty(Optional.empty());
            } else {
                existing = Mono.just(Optional.empty());
            }
            return existing.flatMap(opt -> opt.isPresent()
                    ? Mono.just(opt.get())
                    : contacts.save(buildContact(intake)));
        });
    }

    private Contact buildContact(MolePhotoIntakeParams intake) {
        String name = intake.name();
        String displayName = name != null
                ? name
                : (intake.phone() != null ? "Photo lead " + intake.phone()
                        : (intake.email() != null ? "Photo lead " + intake.email() : "Photo lead"));
        List<PhoneNumber> phones = new ArrayList<>();
        if (intake.phone() != null) {
            phones.add(PhoneNumber.builder().number(intake.phone()).label("mole-triage").build());
        }
        List<EmailContact> emails = new ArrayList<>();
        if (intake.email() != null) {
            emails.add(new EmailContact(intake.email()));
        }
        return Contact.builder()
                .type(ContactType.PERSON)
                .firstName(name)
                .displayName(displayName)
                .phones(phones)
                .emails(emails)
                .tags(Set.of("mole-triage-lead"))
                .build();
    }

    /**
     * Logs the triage via the UNCHANGED {@code ActivityCrudService.create}: type=NOTE (reused, no
     * new {@code ActivityType}), subjectType=CONTACT, summary = classification + confidence one-liner,
     * payload = {classification, confidence, attachmentId, aboveThreshold, address?}.
     */
    private Mono<Activity> logTriageActivity(UUID tenantId, Contact contact,
                                             MoleClassification classification, Attachment attachment,
                                             MolePhotoIntakeParams intake) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classification", classification.category().wire());
        payload.put("confidence", classification.confidence());
        payload.put("aboveThreshold", isAboveThreshold(classification));
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        if (classification.rationale() != null) payload.put("rationale", classification.rationale());
        if (intake.address() != null) payload.put("address", intake.address());

        StringBuilder bodyText = new StringBuilder(classification.toSummaryLine());
        if (intake.address() != null) {
            bodyText.append("\nAddress: ").append(intake.address());
        }

        Activity activity = Activity.builder()
                .tenantId(tenantId)
                .type(ActivityType.NOTE)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contact.getId())
                .summary(classification.toSummaryLine())
                .body(bodyText.toString())
                .payload(payload)
                .build();
        return activityCrudService.create(activity);
    }

    /**
     * Best-effort notify Rob — email + SMS — to the per-tenant targets in the Twilio
     * {@code IntegrationConnection.config} ({@code notifyEmail} / {@code notifyPhone}); NOT
     * hardcoded. A missing connection / missing target / send failure is swallowed
     * ({@code onErrorResume}) so the already-durable lead is never lost.
     */
    private Mono<Void> notifyRob(MoleClassification classification, Attachment attachment,
                                 MolePhotoIntakeParams intake) {
        return TenantContextHolder.required()
                .flatMap(ctx -> connections.findByTenantIdAndProvider(ctx.tenantId(), NOTIFY_PROVIDER))
                .flatMap(conn -> dispatchNotify(conn, classification, attachment, intake))
                .onErrorResume(e -> {
                    log.warn("Mole-triage notify failed (best-effort, ignored): {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> dispatchNotify(IntegrationConnection conn, MoleClassification classification,
                                      Attachment attachment, MolePhotoIntakeParams intake) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");
        String summary = classification.toSummaryLine();
        String callback = intake.phone() != null ? intake.phone()
                : (intake.email() != null ? intake.email() : "(no contact info supplied)");

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            StringBuilder html = new StringBuilder();
            html.append("<p>").append(HtmlUtils.htmlEscape(summary)).append("</p>");
            html.append("<p>Contact: ").append(HtmlUtils.htmlEscape(callback)).append("</p>");
            if (intake.address() != null) {
                html.append("<p>Address: ").append(HtmlUtils.htmlEscape(intake.address())).append("</p>");
            }
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject("New photo-triage lead — " + classification.category().wire())
                    .body(html.toString())
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("Mole-triage notify-email failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }

        Mono<Void> smsMono = Mono.empty();
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(summary + " Contact: " + callback)
                    .build();
            smsMono = twilioSmsService.sendSms(smsReq)
                    .onErrorResume(e -> {
                        log.warn("Mole-triage notify-SMS failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    private MoleTriageResponse buildResponse(MoleClassification classification, boolean above,
                                             UUID attachmentId) {
        String message;
        if (above) {
            message = "Looks like a " + classification.category().wire()
                    + ". Rob will confirm and follow up.";
        } else if (classification.category() == MoleClassificationCategory.NONE) {
            message = "We didn't spot a clear pest sign in this photo — but Rob can take a closer "
                    + "look if you're concerned.";
        } else {
            message = "This one's unclear from the photo — a human will confirm. Rob will follow up.";
        }
        return new MoleTriageResponse(
                classification.category().wire(),
                classification.confidence(),
                above,
                message,
                attachmentId);
    }

    private void emitPhotoClassified(UUID tenantId, MoleClassification classification,
                                     Attachment attachment, boolean above) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classification", classification.category().wire());
        payload.put("confidence", classification.confidence());
        payload.put("aboveThreshold", above);
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.MOLE_PHOTO_CLASSIFIED, tenantId,
                attachment.getId() != null ? attachment.getId() : UUID.randomUUID(), payload));
    }

    private void emitLeadCreated(UUID tenantId, MoleClassification classification,
                                 Attachment attachment, Contact contact, Activity activity) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("classification", classification.category().wire());
        payload.put("confidence", classification.confidence());
        if (contact.getId() != null) payload.put("contactId", contact.getId().toString());
        if (activity.getId() != null) payload.put("activityId", activity.getId().toString());
        if (attachment.getId() != null) payload.put("attachmentId", attachment.getId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.MOLE_LEAD_CREATED, tenantId,
                contact.getId() != null ? contact.getId() : UUID.randomUUID(), payload));
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
