package com.kumouri.kmodigipresbe.integration.twilio.voice;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.integration.TwilioVoicemailEvent;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.twilio.TwilioVoicemailEventRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Receives Twilio voice + voicemail-transcription webhooks scoped to a single tenant and
 * drives the voicemail-to-lead pipeline (Phase 1 — NMM AI intake, Feature A).
 *
 * <h2>Structural mirror of {@code CalComWebhookService} (the canonical webhook pattern)</h2>
 * Every structural choice mirrors the shipped {@code CalComWebhookService}
 * line-shape-for-line-shape:
 * <ol>
 *   <li><strong>Connection lookup → authToken → signature verify → process.</strong>
 *       Not-connected → stable {@code 4001} / 404. Signature failure → stable
 *       {@code 4000} / 401. The {@code X-Twilio-Signature} is checked in exactly one place,
 *       via {@link TwilioRequestValidator} (the adapter boundary).</li>
 *   <li><strong>Explicit-boolean idempotency probe</strong> on the Twilio {@code CallSid}:
 *       {@code voicemailEvents.findByTenantIdAndCallSid(...).map(e -> true)
 *       .defaultIfEmpty(false).flatMap(seen -> seen ? Mono.empty() : processAndRecord(...))}
 *       — <strong>NEVER {@code switchIfEmpty(process)}</strong> (§9 item 1 — the mandated
 *       grep target).</li>
 *   <li><strong>Ledger-insert-FIRST</strong> in {@link #processAndRecord}: the
 *       {@link TwilioVoicemailEvent} row is saved BEFORE any extraction / Contact upsert /
 *       Activity creation / notify. A concurrent re-delivery's second insert hits the unique
 *       {@code tenant_callsid_idx} → {@code DuplicateKeyException} → {@code Mono.empty()} =
 *       zero second effect.</li>
 *   <li><strong>Duplicate delivery → 200 no-op</strong> (NOT 409).</li>
 *   <li><strong>Tenant resolved from the URL path</strong> → the per-tenant
 *       {@code IntegrationConnection} → never from the webhook payload (§9 item).</li>
 *   <li>All work under a synthetic {@code TenantContext(tenantId, null,
 *       Set.of("INTEGRATION_TWILIO"))} via {@code body.contextWrite(...)}.</li>
 * </ol>
 *
 * <p>The lead-extraction (LLM), find-or-create-Contact + Activity, and notify-Rob/auto-ack
 * steps are added in sub-phases 1.3/1.4 and run inside {@link #processAndRecord} under the
 * synthetic context, after the ledger insert. AI is triage, not truth — the transcript +
 * raw recording are always recorded so Rob can verify (plan §8).
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.voicemail-intake", name="enabled",
 * matchIfMissing=true)} — the feature is on by default; the webhook controllers stay always-on
 * under {@code /public/**} (matching how Phase H gated the service/feature, not the public
 * webhook controller). When disabled, this bean is absent → the controllers fail fast (the
 * intended outcome on a server with the module off).
 *
 * <h2>No live Twilio anywhere (§7)</h2>
 * Signatures are verified with a test/sandbox {@code authToken} in tests; the notify + auto-ack
 * SMS go through the existing {@code TwilioSmsService} pointed at WireMock; no host hardcoded;
 * no live call/charge/send in the implementation loop. Provisioning a real Twilio number +
 * call-forwarding is a separate human action.
 */
@Slf4j
@Service
@ConditionalOnProperty(prefix = "kmosf.modules.voicemail-intake", name = "enabled",
        matchIfMissing = true)
public class TwilioVoicemailService {

    public static final String PROVIDER = TwilioSmsService.PROVIDER; // "twilio"

    private final IntegrationConnectionRepository connections;
    private final TwilioVoicemailEventRepository voicemailEvents;
    private final VoicemailTranscriptionSource transcriptionSource;
    private final VoicemailExtractionService extractionService;
    private final ContactRepository contacts;
    private final ActivityCrudService activityCrudService;
    private final EmailService emailService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    private final String greeting;
    private final int recordMaxLengthSeconds;
    private final String autoAckMessage;
    private final String notifyFromAddress;

    public TwilioVoicemailService(
            IntegrationConnectionRepository connections,
            TwilioVoicemailEventRepository voicemailEvents,
            VoicemailTranscriptionSource transcriptionSource,
            VoicemailExtractionService extractionService,
            ContactRepository contacts,
            ActivityCrudService activityCrudService,
            EmailService emailService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.voicemail.greeting:Thank you for calling. Please leave a message "
                    + "with your name, address, and a description of your problem after the "
                    + "beep, and we will call you back.}") String greeting,
            @Value("${kmosf.voicemail.record-max-length-seconds:120}") int recordMaxLengthSeconds,
            @Value("${kmosf.voicemail.auto-ack-message:Thanks for calling — we got your "
                    + "message and will call you back.}") String autoAckMessage,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        this.connections = connections;
        this.voicemailEvents = voicemailEvents;
        this.transcriptionSource = transcriptionSource;
        this.extractionService = extractionService;
        this.contacts = contacts;
        this.activityCrudService = activityCrudService;
        this.emailService = emailService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.greeting = greeting;
        this.recordMaxLengthSeconds = recordMaxLengthSeconds;
        this.autoAckMessage = autoAckMessage;
        this.notifyFromAddress = notifyFromAddress;
    }

    // -------------------------------------------------------------------------
    // Incoming-call voice webhook → TwiML
    // -------------------------------------------------------------------------

    /**
     * Verifies the Twilio signature, then returns the TwiML that greets the caller and
     * records + transcribes a voicemail with the transcription delivered to the
     * {@code .../voicemail} callback. Tenant from path only.
     */
    public Mono<String> handleVoice(UUID tenantId, String signatureHeader, String fullUrl,
                                    MultiValueMap<String, String> form) {
        return verifiedConnection(tenantId, signatureHeader, fullUrl, form)
                .thenReturn(buildVoiceTwiml(tenantId));
    }

    private String buildVoiceTwiml(UUID tenantId) {
        // transcribeCallback points back at THIS controller's voicemail endpoint for the
        // same tenant (relative path — Twilio resolves it against the voice webhook host).
        String callbackPath = "/public/integrations/twilio/" + tenantId + "/voicemail";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response>"
                + "<Say>" + HtmlUtils.htmlEscape(greeting) + "</Say>"
                + "<Record transcribe=\"true\""
                + " transcribeCallback=\"" + callbackPath + "\""
                + " maxLength=\"" + recordMaxLengthSeconds + "\""
                + " playBeep=\"true\"/>"
                + "</Response>";
    }

    // -------------------------------------------------------------------------
    // Transcription/voicemail callback → ledger-first idempotent pipeline
    // -------------------------------------------------------------------------

    /**
     * Entry point called by {@code TwilioVoicemailController}. Verify-before-effect, then the
     * explicit-boolean {@code CallSid} idempotency probe over the ledger-insert-first pipeline.
     */
    public Mono<Void> handleVoicemail(UUID tenantId, String signatureHeader, String fullUrl,
                                      MultiValueMap<String, String> form) {
        return verifiedConnection(tenantId, signatureHeader, fullUrl, form)
                .flatMap(conn -> process(tenantId, conn, VoicemailCallbackParams.parse(form)));
    }

    /**
     * Resolves the tenant's Twilio {@code IntegrationConnection}, then verifies the
     * {@code X-Twilio-Signature} against its {@code authToken} — the ONLY place the signature
     * is checked. Not-connected → {@code 4001}/404; signature failure → {@code 4000}/401.
     * Returns the verified connection (so callers can proceed); emits no effect.
     *
     * <p>{@code switchIfEmpty} here is a <strong>genuine not-found</strong> (the tenant has no
     * Twilio connection) — the §9-permitted use, exactly like {@code CalComWebhookService}.
     */
    private Mono<IntegrationConnection> verifiedConnection(UUID tenantId, String signatureHeader,
                                                           String fullUrl,
                                                           MultiValueMap<String, String> form) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Twilio is not connected for this tenant", 4001, 404)))
                .flatMap(conn -> {
                    String authToken = conn.getSecrets() == null
                            ? null
                            : conn.getSecrets().get("authToken");
                    if (authToken == null || authToken.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Tenant's Twilio authToken is not configured", 4001, 404));
                    }
                    Map<String, String> flat = flatten(form);
                    if (!TwilioRequestValidator.verify(signatureHeader, fullUrl, flat, authToken)) {
                        return Mono.error(new DigiPresBeException(
                                "Twilio request signature invalid", 4000, 401));
                    }
                    return Mono.just(conn);
                });
    }

    private Mono<Void> process(UUID tenantId, IntegrationConnection conn,
                               VoicemailCallbackParams params) {
        String callSid = params.callSid();
        if (callSid == null || callSid.isBlank()) {
            // Defensive: without a CallSid we cannot dedupe — reject (400).
            return Mono.error(new DigiPresBeException(
                    "Twilio voicemail callback is missing its CallSid", 4002, 400));
        }

        // Explicit-boolean idempotency probe on the CallSid (§9 item 1).
        // NOT switchIfEmpty(processAndRecord) — that fires whenever the probe completes empty
        // and would re-process on a cache HIT.
        return voicemailEvents.findByTenantIdAndCallSid(tenantId, callSid)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (Boolean.TRUE.equals(seen)) {
                        log.debug("Twilio voicemail CallSid {} already processed for tenant {} "
                                + "— 200 no-op", callSid, tenantId);
                        return Mono.empty();
                    }
                    return processAndRecord(tenantId, conn, params);
                });
    }

    /**
     * Ledger-insert FIRST (§9 item), then run the lead pipeline under the synthetic
     * {@code TenantContext}.
     *
     * <p>The {@link TwilioVoicemailEvent} row is inserted <strong>before any side effect</strong>
     * so a concurrent re-delivery's second insert hits the unique {@code tenant_callsid_idx} →
     * {@code DuplicateKeyException} → {@code Mono.empty()} = zero second effect.
     *
     * <p>After the ledger insert, under the synthetic context: transcribe → extract → lead +
     * Activity → notify, and emit the advisory {@code VOICEMAIL_RECEIVED}.
     */
    private Mono<Void> processAndRecord(UUID tenantId, IntegrationConnection conn,
                                        VoicemailCallbackParams params) {
        String callSid = params.callSid();

        TwilioVoicemailEvent ledger = TwilioVoicemailEvent.builder()
                .tenantId(tenantId)
                .callSid(callSid)
                .recordingSid(params.recordingSid())
                .fromNumber(params.from())
                .toNumber(params.to())
                .transcriptionStatus(params.transcriptionStatus())
                .receivedAt(Instant.now())
                .build();

        // LEDGER-INSERT FIRST — the unique index is the hard gate against concurrent
        // re-delivery. onErrorResume(DuplicateKeyException) swallows the race winner's
        // duplicate → 200 no-op, zero second effect.
        Mono<Void> body = voicemailEvents.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("Twilio voicemail CallSid {} concurrently processed for tenant {} "
                            + "— 200 no-op", callSid, tenantId);
                    return Mono.empty();
                })
                .flatMap(savedLedger -> {
                    if (savedLedger == null) {
                        // Concurrent duplicate swallowed above — already Mono.empty()
                        return Mono.empty();
                    }
                    emitVoicemailReceived(tenantId, params);
                    return transcriptionSource.transcribe(params)
                            .flatMap(transcript ->
                                    handleTranscript(tenantId, conn, params, transcript, savedLedger))
                            .then();
                });

        TenantContext webhookCtx = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_TWILIO"));
        return body.contextWrite(TenantContextHolder.write(webhookCtx));
    }

    /**
     * Extracts structured lead fields from the transcript (best-effort — an AI budget/upstream
     * failure must NOT drop the lead; plan §8), then (sub-phase 1.4) find-or-creates the caller
     * Contact, logs an {@code Activity(CALL, INBOUND)}, and notifies Rob + auto-acks the caller.
     *
     * <p>The extraction is wrapped in {@code onErrorResume(VoicemailExtraction.empty())}: a
     * {@code 1200} budget-exhausted or {@code 1202} upstream error degrades to an empty
     * extraction so the lead is still created from the raw transcript + recording.
     */
    private Mono<Void> handleTranscript(UUID tenantId, IntegrationConnection conn,
                                        VoicemailCallbackParams params,
                                        VoicemailTranscription transcript,
                                        TwilioVoicemailEvent savedLedger) {
        return extractionService.extract(transcript.text())
                .onErrorResume(e -> {
                    log.warn("Twilio voicemail CallSid {} for tenant {}: extraction failed "
                            + "(best-effort, using empty): {}", params.callSid(), tenantId,
                            e.getMessage());
                    return Mono.just(VoicemailExtraction.empty());
                })
                .flatMap(extraction -> createLeadAndNotify(
                        tenantId, conn, params, transcript, extraction, savedLedger));
    }

    /**
     * The lead path: find-or-create a Contact by the caller phone ({@code From}) → log an
     * {@code Activity(CALL, INBOUND, subjectType=CONTACT)} via the <strong>unchanged</strong>
     * {@code ActivityCrudService.create} with the transcript as body + the extracted summary →
     * best-effort notify Rob (email + SMS, target from per-tenant
     * {@code IntegrationConnection.config}) + auto-ack the caller (SMS) → back-fill the ledger
     * with the resolved contact/activity ids → publish {@code VOICEMAIL_LEAD_CREATED}.
     *
     * <p>Contact find-or-create uses an <strong>explicit-boolean branch</strong> (not
     * {@code switchIfEmpty(create)}) so the only {@code switchIfEmpty} in this package stays the
     * genuine not-connected one (§9). Notify + auto-ack are best-effort
     * ({@code onErrorResume}) — a send failure must NOT fail the ingest (the lead + Activity are
     * already durable; plan §8). All running under the synthetic {@code TenantContext} set by
     * {@link #processAndRecord}.
     */
    private Mono<Void> createLeadAndNotify(UUID tenantId, IntegrationConnection conn,
                                           VoicemailCallbackParams params,
                                           VoicemailTranscription transcript,
                                           VoicemailExtraction extraction,
                                           TwilioVoicemailEvent savedLedger) {
        return findOrCreateContact(tenantId, params, extraction)
                .flatMap(contact -> logCallActivity(tenantId, contact, params, transcript, extraction)
                        .flatMap(activity -> {
                            savedLedger.setResolvedContactId(contact.getId());
                            savedLedger.setCreatedActivityId(activity.getId());
                            return voicemailEvents.save(savedLedger).thenReturn(activity);
                        })
                        .flatMap(activity -> notifyRob(conn, params, extraction)
                                .then(autoAckCaller(params))
                                .then(Mono.fromRunnable(() ->
                                        emitVoicemailLeadCreated(tenantId, params, contact, activity)))))
                .then();
    }

    /**
     * Find-or-create the caller Contact by phone, explicit-boolean (never
     * {@code switchIfEmpty(create)}). An existing contact is returned untouched (we don't
     * rewrite staff-curated data from an inbound call — the ServiceRequest-widget precedent).
     */
    private Mono<Contact> findOrCreateContact(UUID tenantId, VoicemailCallbackParams params,
                                              VoicemailExtraction extraction) {
        String from = params.from();
        if (from == null || from.isBlank()) {
            // No caller-ID number — create an anonymous contact so the lead is never dropped.
            return contacts.save(buildContact(null, extraction));
        }
        return contacts.findByTenantAndPhoneNumber(tenantId, from)
                .next()
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(existing -> existing.isPresent()
                        ? Mono.just(existing.get())
                        : contacts.save(buildContact(from, extraction)));
    }

    private Contact buildContact(String fromPhone, VoicemailExtraction extraction) {
        String name = extraction.name() != null && !extraction.name().isBlank()
                ? extraction.name().trim() : null;
        String displayName = name != null
                ? name
                : (fromPhone != null ? "Voicemail caller " + fromPhone : "Voicemail caller");
        List<PhoneNumber> phones = new ArrayList<>();
        if (fromPhone != null && !fromPhone.isBlank()) {
            phones.add(PhoneNumber.builder().number(fromPhone).label("voicemail").build());
        }
        return Contact.builder()
                .type(ContactType.PERSON)
                .firstName(name)
                .displayName(displayName)
                .phones(phones)
                .tags(Set.of("voicemail-lead"))
                .build();
    }

    /**
     * Logs the inbound call via the UNCHANGED {@code ActivityCrudService.create}: summary = the
     * extracted one-liner, body = the raw transcript (so Rob can always verify), payload =
     * {callSid, recordingUrl, extracted fields}.
     */
    private Mono<Activity> logCallActivity(UUID tenantId, Contact contact,
                                           VoicemailCallbackParams params,
                                           VoicemailTranscription transcript,
                                           VoicemailExtraction extraction) {
        Map<String, Object> payload = new HashMap<>();
        if (params.callSid() != null) payload.put("callSid", params.callSid());
        if (params.recordingSid() != null) payload.put("recordingSid", params.recordingSid());
        if (params.recordingUrl() != null) payload.put("recordingUrl", params.recordingUrl());
        if (params.from() != null) payload.put("fromNumber", params.from());
        Map<String, Object> extractedJson = new HashMap<>();
        if (extraction.name() != null) extractedJson.put("name", extraction.name());
        if (extraction.phone() != null) extractedJson.put("phone", extraction.phone());
        if (extraction.address() != null) extractedJson.put("address", extraction.address());
        if (extraction.problem() != null) extractedJson.put("problem", extraction.problem());
        if (extraction.urgency() != null) extractedJson.put("urgency", extraction.urgency());
        extractedJson.put("callbackRequested", extraction.callbackRequested());
        payload.put("extractedJson", extractedJson);
        payload.put("transcriptionSource", transcript.source().name());

        Activity activity = Activity.builder()
                .tenantId(tenantId)
                .type(ActivityType.CALL)
                .direction(ActivityDirection.INBOUND)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contact.getId())
                .summary(extraction.toSummaryLine())
                .body(transcript.hasText() ? transcript.text() : "(no transcript)")
                .payload(payload)
                .build();
        return activityCrudService.create(activity);
    }

    /**
     * Best-effort notify Rob — email + SMS — to the per-tenant targets in
     * {@code IntegrationConnection.config} ({@code notifyEmail} / {@code notifyPhone}); NOT
     * hardcoded. A missing target or a send failure is swallowed ({@code onErrorResume}) so the
     * already-durable lead is never lost.
     */
    private Mono<Void> notifyRob(IntegrationConnection conn, VoicemailCallbackParams params,
                                 VoicemailExtraction extraction) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");
        String callbackNumber = params.from() != null ? params.from() : "(unknown)";
        String summary = extraction.toSummaryLine();

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            String bodyHtml = "<p>" + HtmlUtils.htmlEscape(summary) + "</p>"
                    + "<p>Callback number: " + HtmlUtils.htmlEscape(callbackNumber) + "</p>";
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject("New voicemail lead — " + callbackNumber)
                    .body(bodyHtml)
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("Voicemail notify-email failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }

        Mono<Void> smsMono = Mono.empty();
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(summary + " Callback: " + callbackNumber)
                    .build();
            smsMono = twilioSmsService.sendSms(smsReq)
                    .onErrorResume(e -> {
                        log.warn("Voicemail notify-SMS failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    /**
     * Best-effort auto-acknowledgement SMS back to the caller ({@code From}) via the existing
     * {@code TwilioSmsService}. Swallowed on failure — never fails the ingest.
     */
    private Mono<Void> autoAckCaller(VoicemailCallbackParams params) {
        String from = params.from();
        if (from == null || from.isBlank()) {
            return Mono.empty();
        }
        SmsCommunicationRequest ack = SmsCommunicationRequest.builder()
                .to(new PhoneContact(from))
                .body(autoAckMessage)
                .build();
        return twilioSmsService.sendSms(ack)
                .onErrorResume(e -> {
                    log.warn("Voicemail caller auto-ack SMS failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.just(false);
                })
                .then();
    }

    private void emitVoicemailLeadCreated(UUID tenantId, VoicemailCallbackParams params,
                                          Contact contact, Activity activity) {
        Map<String, Object> payload = new HashMap<>();
        if (params.callSid() != null) payload.put("callSid", params.callSid());
        if (contact.getId() != null) payload.put("contactId", contact.getId().toString());
        if (activity.getId() != null) payload.put("activityId", activity.getId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.VOICEMAIL_LEAD_CREATED, tenantId,
                contact.getId() != null ? contact.getId() : UUID.randomUUID(), payload));
    }

    private void emitVoicemailReceived(UUID tenantId, VoicemailCallbackParams params) {
        Map<String, Object> payload = new HashMap<>();
        if (params.callSid() != null) payload.put("callSid", params.callSid());
        if (params.from() != null) payload.put("fromNumber", params.from());
        if (params.transcriptionStatus() != null) {
            payload.put("transcriptionStatus", params.transcriptionStatus());
        }
        events.publish(DomainEvent.of(
                DomainEventType.VOICEMAIL_RECEIVED, tenantId, UUID.randomUUID(), payload));
    }

    private static Map<String, String> flatten(MultiValueMap<String, String> form) {
        Map<String, String> flat = new HashMap<>();
        if (form != null) {
            form.forEach((k, v) -> flat.put(k, (v == null || v.isEmpty()) ? "" : v.get(0)));
        }
        return flat;
    }
}
