package com.kumouri.kmodigipresbe.integration.twilio.voice;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.integration.equipmentvision.EquipmentPhotoTokenService;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.VoicemailExtractionStrategy;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.VoicemailExtractionStrategyResolver;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.VoicemailLeadDetails;
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
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.twilio.TwilioVoicemailEventRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
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

    /**
     * HS-3 — the per-tenant {@code IntegrationConnection(twilio).config} key whose presence is the
     * EMERGENCY live-forward gate: when set to the on-call technician's E.164 number, the voice
     * webhook returns the emergency IVR {@code <Gather>}; when absent (NMM / default) the voice
     * webhook is byte-unchanged.
     */
    public static final String CONFIG_ON_CALL_PHONE = "onCallPhone";

    /**
     * HS-3 — the per-tenant {@code IntegrationConnection(twilio).config} key whose presence gates the
     * caller-facing booking-link SMS: when set to a booking URL, the caller auto-ack appends "Book
     * your visit: &lt;url&gt;"; when absent (NMM / default) the auto-ack is byte-unchanged.
     */
    public static final String CONFIG_BOOKING_LINK_URL = "bookingLinkUrl";

    /**
     * HS-2 — TTL of the equipment-photo upload token embedded in the home-services auto-ack SMS link.
     * 7 days (matches {@code EquipmentPhotoTokenController.TOKEN_TTL}): long enough for a caller to
     * get to their unit and photograph it after the after-hours call, short enough that a forgotten
     * link expires.
     */
    private static final java.time.Duration EQUIPMENT_UPLOAD_TOKEN_TTL = java.time.Duration.ofDays(7);

    private final IntegrationConnectionRepository connections;
    private final TwilioVoicemailEventRepository voicemailEvents;
    private final VoicemailTranscriptionSource transcriptionSource;
    private final VoicemailExtractionStrategyResolver strategyResolver;
    private final ContactRepository contacts;
    private final ActivityCrudService activityCrudService;
    private final EmailService emailService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    /**
     * The field-service {@link WorkOrderService} — present only when
     * {@code kmosf.modules.field-service.enabled=true}. Lazily resolved via an
     * {@link ObjectProvider} so the voicemail module still boots on a server with field-service
     * disabled; when absent, a home-services voicemail degrades to Contact+Activity+notify (logged,
     * never errors — plan §4.4).
     */
    private final ObjectProvider<WorkOrderService> workOrderServiceProvider;
    /**
     * HS-2 — mints the {@code equipment-photo} upload token whose link the home-services auto-ack SMS
     * carries (so the caller can text a photo of their unit). Unconditional bean (mirrors
     * {@code MoleTripwireTokenService}); the link is only ever appended for the home-services vertical
     * (when a DRAFT WorkOrder was created AND a base URL is configured), so the mole/default auto-ack
     * SMS body stays byte-identical (the NMM {@code TwilioVoicemailIT} gate).
     */
    private final EquipmentPhotoTokenService equipmentPhotoTokens;

    private final String greeting;
    private final int recordMaxLengthSeconds;
    private final String autoAckMessage;
    private final String notifyFromAddress;
    /**
     * HS-2 — base URL for the public equipment-photo upload link appended to the home-services
     * auto-ack SMS, e.g. {@code https://api-demo.kmosolutionsfoundry.com/api/v1/public/integrations/
     * home-services/equipment-photo}. The {@code {token}/upload} suffix is appended per-call. When
     * blank (the default), <strong>no link is appended</strong> — so the auto-ack is byte-unchanged
     * everywhere until a home-services deployment opts in by setting this.
     */
    private final String equipmentUploadBaseUrl;

    /**
     * HS-3 — the spoken prompt for the EMERGENCY live-forward {@code <Gather>}. Configurable so a
     * deployment can tune the wording; the default tells the caller to press 1 for the on-call tech
     * or stay on the line to leave a message.
     */
    private final String emergencyGatherPrompt;
    /**
     * HS-3 — the {@code <Gather timeout>} (seconds) the caller has to press 1 before the IVR falls
     * through to the voicemail greeting + {@code <Record>}. Short by default so a non-emergency caller
     * is not made to wait.
     */
    private final int emergencyGatherTimeoutSeconds;

    public TwilioVoicemailService(
            IntegrationConnectionRepository connections,
            TwilioVoicemailEventRepository voicemailEvents,
            VoicemailTranscriptionSource transcriptionSource,
            VoicemailExtractionStrategyResolver strategyResolver,
            ContactRepository contacts,
            ActivityCrudService activityCrudService,
            EmailService emailService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            ObjectProvider<WorkOrderService> workOrderServiceProvider,
            EquipmentPhotoTokenService equipmentPhotoTokens,
            @Value("${kmosf.voicemail.greeting:Thank you for calling. Please leave a message "
                    + "with your name, address, and a description of your problem after the "
                    + "beep, and we will call you back.}") String greeting,
            @Value("${kmosf.voicemail.record-max-length-seconds:120}") int recordMaxLengthSeconds,
            @Value("${kmosf.voicemail.auto-ack-message:Thanks for calling — we got your "
                    + "message and will call you back.}") String autoAckMessage,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress,
            @Value("${kmosf.home-services.equipment-upload-base-url:}") String equipmentUploadBaseUrl,
            @Value("${kmosf.home-services.emergency-gather-prompt:If this is an emergency, press 1 "
                    + "now to reach our on-call technician; otherwise, stay on the line to leave a "
                    + "message.}") String emergencyGatherPrompt,
            @Value("${kmosf.home-services.emergency-gather-timeout-seconds:5}")
                    int emergencyGatherTimeoutSeconds) {
        this.connections = connections;
        this.voicemailEvents = voicemailEvents;
        this.transcriptionSource = transcriptionSource;
        this.strategyResolver = strategyResolver;
        this.contacts = contacts;
        this.activityCrudService = activityCrudService;
        this.emailService = emailService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.workOrderServiceProvider = workOrderServiceProvider;
        this.equipmentPhotoTokens = equipmentPhotoTokens;
        this.greeting = greeting;
        this.recordMaxLengthSeconds = recordMaxLengthSeconds;
        this.autoAckMessage = autoAckMessage;
        this.notifyFromAddress = notifyFromAddress;
        this.equipmentUploadBaseUrl = equipmentUploadBaseUrl == null ? "" : equipmentUploadBaseUrl.trim();
        this.emergencyGatherPrompt = emergencyGatherPrompt;
        this.emergencyGatherTimeoutSeconds = emergencyGatherTimeoutSeconds;
    }

    /**
     * Reads a per-tenant {@code IntegrationConnection.config} value, trimmed, returning {@code null}
     * for absent/blank — the gate posture shared by the HS-3 {@code onCallPhone} /
     * {@code bookingLinkUrl} keys (an absent key keeps the mole/default behavior byte-unchanged).
     */
    private static String configValue(IntegrationConnection conn, String key) {
        if (conn == null || conn.getConfig() == null) {
            return null;
        }
        String v = conn.getConfig().get(key);
        if (v == null) {
            return null;
        }
        String t = v.trim();
        return t.isEmpty() ? null : t;
    }

    // -------------------------------------------------------------------------
    // Incoming-call voice webhook → TwiML
    // -------------------------------------------------------------------------

    /**
     * Verifies the Twilio signature, then returns the TwiML for the inbound call.
     *
     * <h2>HS-3 — EMERGENCY live-forward IVR gate</h2>
     * AI urgency is only known <em>after</em> transcription + triage — which is <em>after</em> the
     * caller has already left a voicemail — so the live-forward CANNOT be AI-gated mid-call (plan §6
     * timing). Instead a <strong>deterministic IVR gate</strong> on the voice webhook: when the
     * tenant's Twilio {@code IntegrationConnection.config} carries an {@code onCallPhone}, the TwiML
     * is a brief {@code <Gather numDigits="1">} ("press 1 now to reach our on-call technician;
     * otherwise stay on the line to leave a message") whose digit is handled by
     * {@link #handleGather} — {@code 1} → {@code <Dial>onCallPhone</Dial>}, anything else / no input /
     * timeout → falls through to the existing greeting + {@code <Record>} voicemail TwiML.
     *
     * <p><strong>The gate:</strong> when {@code onCallPhone} is absent (NMM / the default), this
     * returns {@link #buildVoiceTwiml()} <strong>byte-unchanged</strong> — the NMM voice flow must not
     * change (the {@code TwilioVoicemailIT} {@code voiceEndpoint_signed_returnsRecordTwiml} gate).
     * Tenant from path only.
     */
    public Mono<String> handleVoice(UUID tenantId, String signatureHeader, String fullUrl,
                                    MultiValueMap<String, String> form) {
        return verifiedConnection(tenantId, signatureHeader, fullUrl, form)
                .map(this::buildVoiceTwimlFor);
    }

    /**
     * HS-3 gather callback — handles the single digit from the emergency IVR {@code <Gather>}. Signed
     * exactly like the voice/voicemail callbacks (reused {@code 4000-4003} via
     * {@link #verifiedConnection}). {@code Digits == "1"} → {@code <Dial>onCallPhone</Dial>} (the
     * live-forward to the on-call tech); anything else / no input / timeout → the existing greeting +
     * {@code <Record>} voicemail TwiML (so the caller still leaves a message). If {@code onCallPhone}
     * is somehow absent at this point (defensive — the gather is only ever reached when it was set),
     * also falls through to the record TwiML. Tenant from path only.
     */
    public Mono<String> handleGather(UUID tenantId, String signatureHeader, String fullUrl,
                                     MultiValueMap<String, String> form) {
        return verifiedConnection(tenantId, signatureHeader, fullUrl, form)
                .map(conn -> {
                    String digits = form == null ? null : form.getFirst("Digits");
                    String onCallPhone = configValue(conn, CONFIG_ON_CALL_PHONE);
                    if ("1".equals(digits) && onCallPhone != null) {
                        return buildDialTwiml(onCallPhone);
                    }
                    // Any other / no input / timeout → fall through to the record-voicemail TwiML.
                    return buildVoiceTwiml();
                });
    }

    /**
     * HS-3 — chooses the voice-webhook TwiML for this connection. With an {@code onCallPhone}
     * configured, the emergency IVR {@code <Gather>}; otherwise the existing record-voicemail TwiML
     * <strong>byte-unchanged</strong> (the NMM gate).
     */
    private String buildVoiceTwimlFor(IntegrationConnection conn) {
        String onCallPhone = configValue(conn, CONFIG_ON_CALL_PHONE);
        if (onCallPhone == null) {
            return buildVoiceTwiml();
        }
        return buildEmergencyGatherTwiml();
    }

    /**
     * The emergency-IVR TwiML: a brief {@code <Gather numDigits="1">} prompting the caller to press 1
     * for the on-call tech, posting the digit to the path-relative {@code voice/gather} callback (same
     * RFC-3986 path-relative-resolution reasoning as {@code voicemail} in {@link #buildVoiceTwiml()} —
     * it inherits the host + {@code /api/v1} base-path). On no input / timeout the {@code <Gather>}
     * falls through to the greeting + {@code <Record>} so the caller still leaves a message.
     */
    private String buildEmergencyGatherTwiml() {
        // "voice/gather" is path-relative (no leading slash) so Twilio resolves it against the full
        // voice-webhook URL (.../{id}/voice) → .../{id}/voice/gather, inheriting host + base-path.
        String gatherAction = "voice/gather";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response>"
                + "<Gather numDigits=\"1\" timeout=\"" + emergencyGatherTimeoutSeconds + "\""
                + " action=\"" + gatherAction + "\">"
                + "<Say>" + HtmlUtils.htmlEscape(emergencyGatherPrompt) + "</Say>"
                + "</Gather>"
                + "<Say>" + HtmlUtils.htmlEscape(greeting) + "</Say>"
                + "<Record transcribe=\"true\""
                + " transcribeCallback=\"voicemail\""
                + " maxLength=\"" + recordMaxLengthSeconds + "\""
                + " playBeep=\"true\"/>"
                + "</Response>";
    }

    /**
     * The live-forward TwiML — {@code <Dial>onCallPhone</Dial>}. Telephony is stubbed in tests (no
     * real call is placed); go-live requires a real Twilio number + a verified on-call number
     * (plan §6).
     */
    private String buildDialTwiml(String onCallPhone) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response>"
                + "<Dial>" + HtmlUtils.htmlEscape(onCallPhone) + "</Dial>"
                + "</Response>";
    }

    private String buildVoiceTwiml() {
        // transcribeCallback MUST be a path-relative reference ("voicemail", no leading
        // slash). Twilio resolves it per RFC 3986 against the full voice-webhook URL it
        // fetched (e.g. https://host/api/v1/public/integrations/twilio/{id}/voice), so the
        // resolved callback inherits BOTH the host AND the deployment base-path →
        // .../{id}/voicemail. A leading-slash "/public/..." would resolve against the host
        // ROOT and silently DROP the /api/v1 base-path → a 404 callback (transcription never
        // delivered, no lead created). Verified end-to-end against the live api-demo tunnel.
        String callbackPath = "voicemail";
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
     * Resolves the per-tenant {@link VoicemailExtractionStrategy} from the Twilio connection's
     * {@code voicemailVertical} config value (absent/unknown → the default mole strategy), extracts
     * structured lead fields from the transcript (best-effort — an AI budget/upstream failure must
     * NOT drop the lead; plan §8), then find-or-creates the caller Contact, logs an
     * {@code Activity(CALL, INBOUND)}, optionally creates a DRAFT {@code WorkOrder} (multi-trade
     * verticals), and notifies Rob + auto-acks the caller.
     *
     * <p>The extraction is wrapped in {@code onErrorResume}: a {@code 1200} budget-exhausted or
     * {@code 1202} upstream error degrades to an empty {@link VoicemailLeadDetails} so the lead is
     * still created from the raw transcript + recording. (The multi-trade strategy degrades soft
     * on its own — a failed extraction still yields a {@code GENERAL} DRAFT WO — but an
     * <em>upstream-thrown</em> error short-circuits before that mapping runs, so the same empty
     * carrier fallback applies here.)
     */
    private Mono<Void> handleTranscript(UUID tenantId, IntegrationConnection conn,
                                        VoicemailCallbackParams params,
                                        VoicemailTranscription transcript,
                                        TwilioVoicemailEvent savedLedger) {
        String vertical = conn.getConfig() == null ? null : conn.getConfig().get("voicemailVertical");
        VoicemailExtractionStrategy strategy = strategyResolver.forVertical(vertical);
        return strategy.extract(transcript.text(), params)
                .onErrorResume(e -> {
                    log.warn("Twilio voicemail CallSid {} for tenant {}: extraction failed "
                            + "(best-effort, using empty): {}", params.callSid(), tenantId,
                            e.getMessage());
                    return Mono.just(emptyDetails());
                })
                .flatMap(details -> createLeadAndNotify(
                        tenantId, conn, params, transcript, details, savedLedger, strategy));
    }

    /**
     * An all-empty {@link VoicemailLeadDetails} (no WorkOrder) — the best-effort fallback when a
     * strategy's extraction throws upstream. Mirrors the Phase-1 {@code VoicemailExtraction.empty()}
     * posture: the lead is still created from the raw transcript + recording.
     */
    private static VoicemailLeadDetails emptyDetails() {
        Map<String, Object> extractedJson = new HashMap<>();
        extractedJson.put("callbackRequested", false);
        return new VoicemailLeadDetails(null, null, null, false,
                "Voicemail lead", extractedJson, null);
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
                                           VoicemailLeadDetails details,
                                           TwilioVoicemailEvent savedLedger,
                                           VoicemailExtractionStrategy strategy) {
        return findOrCreateContact(tenantId, params, details)
                .flatMap(contact -> logCallActivity(tenantId, contact, params, transcript, details, strategy)
                        .flatMap(activity -> maybeCreateWorkOrder(tenantId, params, details)
                                .flatMap(woId -> {
                                    savedLedger.setResolvedContactId(contact.getId());
                                    savedLedger.setCreatedActivityId(activity.getId());
                                    savedLedger.setCreatedWorkOrderId(woId.orElse(null));
                                    return voicemailEvents.save(savedLedger)
                                            .thenReturn(woId);
                                })
                                .flatMap(woId -> notifyRob(conn, params, details)
                                        .then(autoAckCaller(tenantId, conn, params, woId))
                                        .then(Mono.fromRunnable(() -> {
                                            emitVoicemailLeadCreated(tenantId, params, contact, activity);
                                            woId.ifPresent(id -> emitVoicemailWorkOrderDrafted(
                                                    tenantId, params, contact, id, details));
                                        }))))
                )
                .then();
    }

    /**
     * Creates the DRAFT {@link WorkOrder} the strategy built (multi-trade verticals), via the
     * <strong>unchanged {@code WorkOrderService.create}</strong> (which server-assigns the
     * {@code workOrderNumber} via {@code WorkOrderNumberGenerator} under the already-established
     * synthetic {@code TenantContext}, and defaults the status to DRAFT) — NOT a raw
     * {@code workOrders.save}. Returns the new WorkOrder id (or empty when the vertical built no
     * WorkOrder, i.e. mole).
     *
     * <p>Guarded on the {@link WorkOrderService} bean via {@link #workOrderServiceProvider}: on a
     * server with {@code field-service} disabled the bean is absent, so a home-services voicemail
     * degrades to Contact+Activity+notify (logged advisory {@code 4201}, never errors — plan §4.4).
     */
    private Mono<java.util.Optional<UUID>> maybeCreateWorkOrder(UUID tenantId,
                                                                VoicemailCallbackParams params,
                                                                VoicemailLeadDetails details) {
        WorkOrder draft = details.draftWorkOrder();
        if (draft == null) {
            return Mono.just(java.util.Optional.empty());
        }
        WorkOrderService workOrderService = workOrderServiceProvider.getIfAvailable();
        if (workOrderService == null) {
            // 4201 — home-services voicemail produced a DRAFT WO but field-service/WorkOrderService
            // is unavailable on this server. Advisory: degrade to Contact+Activity (already done),
            // never surface to Twilio.
            log.warn("Twilio voicemail CallSid {} for tenant {}: a DRAFT WorkOrder was extracted but "
                    + "field-service/WorkOrderService is not available on this server — degrading to "
                    + "Contact+Activity+notify (advisory 4201)", params.callSid(), tenantId);
            return Mono.just(java.util.Optional.empty());
        }
        return workOrderService.create(draft)
                .map(wo -> java.util.Optional.of(wo.getId()))
                .onErrorResume(e -> {
                    // Best-effort: a WO-create failure must not drop the already-durable lead.
                    log.warn("Twilio voicemail CallSid {} for tenant {}: DRAFT WorkOrder create failed "
                            + "(best-effort, lead kept): {}", params.callSid(), tenantId, e.getMessage());
                    return Mono.just(java.util.Optional.empty());
                });
    }

    /**
     * Find-or-create the caller Contact by phone, explicit-boolean (never
     * {@code switchIfEmpty(create)}). An existing contact is returned untouched (we don't
     * rewrite staff-curated data from an inbound call — the ServiceRequest-widget precedent).
     */
    private Mono<Contact> findOrCreateContact(UUID tenantId, VoicemailCallbackParams params,
                                              VoicemailLeadDetails details) {
        String from = params.from();
        if (from == null || from.isBlank()) {
            // No caller-ID number — create an anonymous contact so the lead is never dropped.
            return contacts.save(buildContact(null, details));
        }
        return contacts.findByTenantAndPhoneNumber(tenantId, from)
                .next()
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(existing -> existing.isPresent()
                        ? Mono.just(existing.get())
                        : contacts.save(buildContact(from, details)));
    }

    private Contact buildContact(String fromPhone, VoicemailLeadDetails details) {
        String name = details.name() != null && !details.name().isBlank()
                ? details.name().trim() : null;
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
     * FD-3 fence F2 — the fixed redaction marker used as the {@code Activity.body} for a
     * PHI-sensitive vertical (a strategy whose {@link VoicemailExtractionStrategy#persistTranscript()}
     * is {@code false}). The raw transcript is NEVER stored; only the logistics fields the strategy
     * surfaces (name / callback number / intent bucket) live on the record.
     */
    public static final String TRANSCRIPT_REDACTED_MARKER =
            "(voicemail transcript not retained — front-desk callback)";

    /**
     * Logs the inbound call via the UNCHANGED {@code ActivityCrudService.create}: summary = the
     * extracted one-liner, body = the raw transcript (so Rob can always verify), payload =
     * {callSid, recordingUrl, extracted fields}.
     *
     * <h2>FD-3 fence F2 — transcript-suppression seam</h2>
     * The body (and the recording pointer) is gated on {@link VoicemailExtractionStrategy#persistTranscript()}:
     * <ul>
     *   <li><strong>{@code true} (the default — mole + multi-trade, which do not override it):</strong> the
     *       raw transcript is stored as {@code Activity.body} <strong>byte-identically</strong> to before
     *       (the {@code TwilioVoicemailIT} / {@code HomeServicesVoicemailIT} regression gates), and the
     *       recording SID/URL pointer is retained in the payload as before.</li>
     *   <li><strong>{@code false} (the health front-desk strategy):</strong> the raw transcript is NOT
     *       written — {@code body} becomes the fixed {@link #TRANSCRIPT_REDACTED_MARKER}, and the
     *       recording SID/URL (which could be re-fetched to recover the spoken words) is omitted from the
     *       payload. Only the strategy's logistics-only {@code extractedJson} (name / callbackNumber /
     *       intentBucket) plus the call/from metadata survive — PHI never lands on a stored Activity.</li>
     * </ul>
     * The {@code extractedJson} echo is the strategy's own map: the health strategy populates it with
     * logistics fields only (no transcript text, no clinical token), so it is safe to store under either
     * branch. {@code transcriptionSource} is the source enum name (e.g. {@code TWILIO_BUILTIN}) — not PHI.
     */
    private Mono<Activity> logCallActivity(UUID tenantId, Contact contact,
                                           VoicemailCallbackParams params,
                                           VoicemailTranscription transcript,
                                           VoicemailLeadDetails details,
                                           VoicemailExtractionStrategy strategy) {
        boolean persistTranscript = strategy.persistTranscript();

        Map<String, Object> payload = new HashMap<>();
        if (params.callSid() != null) payload.put("callSid", params.callSid());
        if (persistTranscript) {
            // The recording SID/URL are a pointer back to the spoken words — suppressed alongside the
            // transcript for a PHI-sensitive vertical (F2), retained byte-identically otherwise.
            if (params.recordingSid() != null) payload.put("recordingSid", params.recordingSid());
            if (params.recordingUrl() != null) payload.put("recordingUrl", params.recordingUrl());
        }
        if (params.from() != null) payload.put("fromNumber", params.from());
        payload.put("extractedJson", details.extractedJson());
        payload.put("transcriptionSource", transcript.source().name());

        String body = persistTranscript
                ? (transcript.hasText() ? transcript.text() : "(no transcript)")
                : TRANSCRIPT_REDACTED_MARKER;

        Activity activity = Activity.builder()
                .tenantId(tenantId)
                .type(ActivityType.CALL)
                .direction(ActivityDirection.INBOUND)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contact.getId())
                .summary(details.summaryLine())
                .body(body)
                .payload(payload)
                .build();
        return activityCrudService.create(activity);
    }

    /**
     * Best-effort notify Rob — email + SMS — to the per-tenant targets in
     * {@code IntegrationConnection.config} ({@code notifyEmail} / {@code notifyPhone}); NOT
     * hardcoded. A missing target or a send failure is swallowed ({@code onErrorResume}) so the
     * already-durable lead is never lost.
     *
     * <p>HS-3: when the AI triage urgency is {@code EMERGENCY} (the HS-1
     * {@code extractedJson.urgency} the multi-trade strategy stamps), the owner digest is prominently
     * <strong>EMERGENCY-flagged</strong> (an {@code EMERGENCY:} prefix on the email subject + an
     * {@code EMERGENCY} banner on the email body + SMS). For the mole/default vertical (no
     * {@code urgency} / a non-EMERGENCY urgency) the email subject + email body + SMS body are
     * <strong>byte-identical</strong> to before (the NMM {@code TwilioVoicemailIT} gate).
     */
    private Mono<Void> notifyRob(IntegrationConnection conn, VoicemailCallbackParams params,
                                 VoicemailLeadDetails details) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");
        String callbackNumber = params.from() != null ? params.from() : "(unknown)";
        String summary = details.summaryLine();
        boolean emergency = isEmergency(details);

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            // The EMERGENCY banner is prepended only for an EMERGENCY-urgency lead; otherwise the
            // body is the byte-identical Phase-1/HS-1 construction.
            String bodyHtml = (emergency ? "<p><strong>EMERGENCY</strong></p>" : "")
                    + "<p>" + HtmlUtils.htmlEscape(summary) + "</p>"
                    + "<p>Callback number: " + HtmlUtils.htmlEscape(callbackNumber) + "</p>";
            String subject = (emergency ? "EMERGENCY: " : "")
                    + "New voicemail lead — " + callbackNumber;
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject(subject)
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
            // EMERGENCY prefix only for an EMERGENCY lead; otherwise byte-identical to before.
            String smsBody = (emergency ? "EMERGENCY: " : "")
                    + summary + " Callback: " + callbackNumber;
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(smsBody)
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
     * HS-3 — whether the AI triage urgency for this lead is {@code EMERGENCY}. Reads the
     * {@code extractedJson.urgency} the multi-trade strategy stamps (the same value echoed onto the
     * WorkOrder {@code customFields.urgency}). Defensive: a missing/non-EMERGENCY value → {@code false}
     * (so the mole/default digest stays byte-unchanged).
     */
    private static boolean isEmergency(VoicemailLeadDetails details) {
        Object urgency = details.extractedJson().get("urgency");
        return urgency != null && "EMERGENCY".equals(urgency.toString());
    }

    /**
     * Best-effort auto-acknowledgement SMS back to the caller ({@code From}) via the existing
     * {@code TwilioSmsService}. Swallowed on failure — never fails the ingest.
     *
     * <p>HS-2: when a DRAFT {@link WorkOrder} was created (home-services vertical only) AND a
     * {@code kmosf.home-services.equipment-upload-base-url} is configured, the equipment-photo upload
     * link is appended so the caller can text a photo of their unit (which
     * {@code EquipmentVisionService} reads to enrich the WorkOrder).
     *
     * <p>HS-3: when the tenant's Twilio {@code config.bookingLinkUrl} is set, a booking link is
     * appended so the caller can self-book ("Book your visit: &lt;url&gt;"). Go-live for the
     * caller-facing booking SMS needs an A2P 10DLC campaign (plan §6) — out of the implementation
     * loop.
     *
     * <p>For the mole/default vertical {@code woId} is empty AND (in NMM's config) neither the
     * equipment-upload base URL nor {@code bookingLinkUrl} is set → no link of either kind → the SMS
     * body is <strong>byte-identical</strong> to before (the NMM {@code TwilioVoicemailIT} gate).
     */
    private Mono<Void> autoAckCaller(UUID tenantId, IntegrationConnection conn,
                                     VoicemailCallbackParams params, java.util.Optional<UUID> woId) {
        String from = params.from();
        if (from == null || from.isBlank()) {
            return Mono.empty();
        }
        SmsCommunicationRequest ack = SmsCommunicationRequest.builder()
                .to(new PhoneContact(from))
                .body(buildAutoAckBody(tenantId, conn, woId))
                .build();
        return twilioSmsService.sendSms(ack)
                .onErrorResume(e -> {
                    log.warn("Voicemail caller auto-ack SMS failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.just(false);
                })
                .then();
    }

    /**
     * Builds the auto-ack SMS body. The base message is unchanged; two <em>independent, gated</em>
     * suffixes may be appended (in order): the HS-2 equipment-photo upload link (when {@code woId} is
     * present AND the upload base URL is configured) and the HS-3 booking link (when the tenant's
     * Twilio {@code config.bookingLinkUrl} is set). Token minting is wrapped defensively — a token
     * failure must never fail the (best-effort) auto-ack, so it degrades to omitting that one suffix.
     *
     * <p>When neither suffix applies (the mole/default path) the returned body is
     * <strong>byte-identical</strong> to {@link #autoAckMessage} — the NMM gate.
     */
    private String buildAutoAckBody(UUID tenantId, IntegrationConnection conn,
                                    java.util.Optional<UUID> woId) {
        StringBuilder body = new StringBuilder(autoAckMessage);

        // HS-2 — equipment-photo upload link (gated on a DRAFT WO + configured base URL).
        if (woId.isPresent() && !equipmentUploadBaseUrl.isEmpty()) {
            try {
                String token = equipmentPhotoTokens.issue(
                        tenantId, woId.get(), EQUIPMENT_UPLOAD_TOKEN_TTL);
                String base = equipmentUploadBaseUrl.endsWith("/")
                        ? equipmentUploadBaseUrl.substring(0, equipmentUploadBaseUrl.length() - 1)
                        : equipmentUploadBaseUrl;
                String link = base + "/" + token + "/upload";
                body.append(" If it helps, send a photo of your equipment here: ").append(link);
            } catch (RuntimeException e) {
                log.warn("Equipment-photo upload-link minting failed for WorkOrder {} (best-effort, "
                        + "omitting the upload link): {}", woId.get(), e.getMessage());
            }
        }

        // HS-3 — caller booking link (gated on the per-tenant config.bookingLinkUrl).
        String bookingLinkUrl = configValue(conn, CONFIG_BOOKING_LINK_URL);
        if (bookingLinkUrl != null) {
            body.append(" Book your visit: ").append(bookingLinkUrl);
        }

        return body.toString();
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

    /**
     * Advisory {@code VOICEMAIL_WORK_ORDER_DRAFTED} — emitted after a multi-trade-tenant voicemail
     * creates a DRAFT {@link WorkOrder}. Does NOT drive the WO creation (that is synchronous in
     * {@link #maybeCreateWorkOrder}); RuleEngine / webhook fan-out subscribe. Payload:
     * {@code {callSid, workOrderId, contactId, trade, urgency}}.
     */
    private void emitVoicemailWorkOrderDrafted(UUID tenantId, VoicemailCallbackParams params,
                                               Contact contact, UUID workOrderId,
                                               VoicemailLeadDetails details) {
        Map<String, Object> payload = new HashMap<>();
        if (params.callSid() != null) payload.put("callSid", params.callSid());
        payload.put("workOrderId", workOrderId.toString());
        if (contact.getId() != null) payload.put("contactId", contact.getId().toString());
        Object trade = details.extractedJson().get("trade");
        if (trade != null) payload.put("trade", trade);
        Object urgency = details.extractedJson().get("urgency");
        if (urgency != null) payload.put("urgency", urgency);
        events.publish(DomainEvent.of(
                DomainEventType.VOICEMAIL_WORK_ORDER_DRAFTED, tenantId, workOrderId, payload));
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
