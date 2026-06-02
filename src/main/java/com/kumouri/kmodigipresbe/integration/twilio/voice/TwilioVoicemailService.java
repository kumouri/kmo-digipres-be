package com.kumouri.kmodigipresbe.integration.twilio.voice;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.integration.TwilioVoicemailEvent;
import com.kumouri.kmodigipresbe.repository.twilio.TwilioVoicemailEventRepository;
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
import java.util.HashMap;
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
    private final DomainEventPublisher events;

    private final String greeting;
    private final int recordMaxLengthSeconds;

    public TwilioVoicemailService(
            IntegrationConnectionRepository connections,
            TwilioVoicemailEventRepository voicemailEvents,
            VoicemailTranscriptionSource transcriptionSource,
            VoicemailExtractionService extractionService,
            DomainEventPublisher events,
            @Value("${kmosf.voicemail.greeting:Thank you for calling. Please leave a message "
                    + "with your name, address, and a description of your problem after the "
                    + "beep, and we will call you back.}") String greeting,
            @Value("${kmosf.voicemail.record-max-length-seconds:120}") int recordMaxLengthSeconds) {
        this.connections = connections;
        this.voicemailEvents = voicemailEvents;
        this.transcriptionSource = transcriptionSource;
        this.extractionService = extractionService;
        this.events = events;
        this.greeting = greeting;
        this.recordMaxLengthSeconds = recordMaxLengthSeconds;
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
                .flatMap(conn -> process(tenantId, VoicemailCallbackParams.parse(form)));
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

    private Mono<Void> process(UUID tenantId, VoicemailCallbackParams params) {
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
                    return processAndRecord(tenantId, params);
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
     * <p>Sub-phases 1.3/1.4 extend the post-ledger body with extraction + lead + Activity +
     * notify; for now it ledgers, transcribes, and emits the advisory {@code VOICEMAIL_RECEIVED}.
     */
    private Mono<Void> processAndRecord(UUID tenantId, VoicemailCallbackParams params) {
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
                            .flatMap(transcript -> handleTranscript(tenantId, params, transcript, savedLedger))
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
    private Mono<Void> handleTranscript(UUID tenantId, VoicemailCallbackParams params,
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
                        tenantId, params, transcript, extraction, savedLedger));
    }

    /**
     * Sub-phase 1.4 fills this in: find-or-create Contact by caller phone → log
     * {@code Activity(CALL, INBOUND)} with the transcript + extracted summary → best-effort
     * notify Rob + auto-ack caller → publish {@code VOICEMAIL_LEAD_CREATED}. For 1.3 it is a
     * logged no-op (the extraction is computed + recorded above).
     */
    private Mono<Void> createLeadAndNotify(UUID tenantId, VoicemailCallbackParams params,
                                           VoicemailTranscription transcript,
                                           VoicemailExtraction extraction,
                                           TwilioVoicemailEvent savedLedger) {
        log.debug("Twilio voicemail CallSid {} for tenant {}: extracted '{}' (transcript "
                        + "source={}, hasText={})", params.callSid(), tenantId,
                extraction.toSummaryLine(), transcript.source(), transcript.hasText());
        return Mono.empty();
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
