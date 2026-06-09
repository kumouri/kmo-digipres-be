package com.kumouri.kmodigipresbe.integration.twilio;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.voice.TwilioRequestValidator;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.chairfill.gapfill.WaitlistClaimService;
import com.kumouri.kmodigipresbe.module.realestate.concierge.ConciergeInboundRouter;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.responder.InboundIntentRouter;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import org.springframework.util.MultiValueMap;

import jakarta.annotation.Nullable;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ChairFill CF-3 — receives inbound Twilio <em>SMS</em> webhooks scoped to a single tenant. This is the
 * net-new inbound-SMS route the gap-fill "first YES claims the slot" loop needs (HS shipped voicemail +
 * voice webhooks, but no inbound-SMS route — verified on {@code main}).
 *
 * <h2>Structural mirror of {@code TwilioVoicemailService} (the canonical webhook pattern)</h2>
 * <ol>
 *   <li><strong>Connection lookup → authToken → signature verify → process.</strong> Not-connected →
 *       stable {@code 4001}/404. Signature failure → stable {@code 4000}/401. The
 *       {@code X-Twilio-Signature} is checked in exactly one place via {@link TwilioRequestValidator}
 *       (the adapter boundary). Reuses the voicemail webhook's {@code 4000-4003} codes verbatim.</li>
 *   <li><strong>Tenant resolved from the URL path</strong> → the per-tenant {@code IntegrationConnection}
 *       → never the webhook payload (§9 external-ingress invariant).</li>
 *   <li>All work under a synthetic {@code TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"))}.</li>
 * </ol>
 *
 * <h2>Body routing</h2>
 * Twilio POSTs {@code {From, To, Body, MessageSid, ...}}. The trimmed, upper-cased {@code Body} is
 * classified:
 * <ul>
 *   <li><strong>STOP</strong> family ({@code STOP/STOPALL/UNSUBSCRIBE/CANCEL/END/QUIT}) → set the CF-2
 *       {@value RiskTieredPreventionService#SMS_OPT_OUT_TAG} tag on the matching contact(s) for the
 *       sender's {@code From} number (honors TCPA STOP — closes the CF-2 follow-up). Idempotent.</li>
 *   <li><strong>Affirmative</strong> ({@code YES/Y/YEAH/YEP/YUP/SURE/OK/OKAY}) → the gap-fill atomic
 *       claim ({@link WaitlistClaimService#handleAffirmative}): the first YES wins the freed slot, a
 *       late YES gets the apology.</li>
 *   <li>anything else → no-op (logged); Twilio still gets a 2xx.</li>
 * </ul>
 *
 * <p>No idempotency ledger on {@code MessageSid}: a re-delivered YES is naturally idempotent (the slot is
 * already CLAIMED so a re-claim is a loser → apology, never a second booking — the atomic guard handles
 * it), and a re-delivered STOP is an idempotent tag-set. This matches the
 * {@code MoleTriageController}/{@code MoleTripwireController} "intentionally re-invocable public surface"
 * posture rather than the voicemail ledger.
 *
 * <h2>Module gate</h2>
 * Wired as a {@code @Bean} only when {@code kmosf.modules.chairfill.enabled} + the salon-spa beans are
 * present (and, when chairfill is off, by {@code RealEstateAutoConfiguration} so the inbound webhook still
 * exists for a pure-realestate tenant); the controller is gated on the presence of this bean (absent from
 * the OpenAPI spec when no module provides it). No live Twilio anywhere (§7).
 *
 * <h2>Real Estate Concierge seam (RE-1)</h2>
 * A per-tenant {@code smsMode} (read from the verified {@code IntegrationConnection(twilio).config.smsMode},
 * default/absent = {@code "chairfill"}) routes the inbound. <strong>STOP-words still win first</strong>
 * (TCPA) regardless of mode. When {@code smsMode="realestate"} and a {@link ConciergeInboundRouter} is
 * wired (only when the realestate module is on), the body is delegated to that router (the grounded
 * concierge). When {@code smsMode} is absent/{@code "chairfill"} <strong>the existing ChairFill YES/STOP
 * path is byte-identical</strong> — the seam is a no-op (the router is null / never consulted), so the
 * shipped {@code GapFillWaitlistIT} inbound cases pass unchanged.
 */
@Slf4j
public class InboundSmsService {

    public static final String PROVIDER = TwilioSmsService.PROVIDER; // "twilio"

    /** The per-tenant routing mode key on {@code IntegrationConnection(twilio).config} (RE-1 §6.6). */
    public static final String SMS_MODE_KEY = "smsMode";
    /** The realestate routing mode value that flips the inbound to the grounded concierge. */
    public static final String SMS_MODE_REALESTATE = "realestate";

    private static final Set<String> STOP_WORDS = Set.of(
            "STOP", "STOPALL", "UNSUBSCRIBE", "CANCEL", "END", "QUIT", "REVOKE", "OPTOUT", "OPT-OUT");
    private static final Set<String> YES_WORDS = Set.of(
            "YES", "Y", "YEAH", "YEP", "YUP", "SURE", "OK", "OKAY", "CONFIRM");

    private final IntegrationConnectionRepository connections;
    private final ContactRepository contacts;

    /**
     * The CF-3 gap-fill claim service — present only when ChairFill (the YES path) is on. Null in a
     * pure-realestate deployment, where the YES path is never reached (the concierge seam owns all
     * non-STOP bodies). The STOP/opt-out path never touches it.
     */
    @Nullable
    private final WaitlistClaimService claimService;

    /**
     * The realestate inbound router — wired (via {@link #setConciergeRouter}) only when the realestate
     * module is loaded. Null otherwise → the {@code smsMode} seam is inert and the ChairFill YES/STOP
     * path is byte-identical. The bean is hand-constructed (not component-scanned), so this is a setter
     * the realestate auto-config invokes, not field-{@code @Autowired}.
     */
    @Nullable
    private ConciergeInboundRouter conciergeRouter;

    /** ChairFill (CF-3) constructor — the YES path is wired via {@code claimService}. */
    public InboundSmsService(IntegrationConnectionRepository connections,
                             ContactRepository contacts,
                             WaitlistClaimService claimService) {
        this.connections = connections;
        this.contacts = contacts;
        this.claimService = claimService;
    }

    /**
     * Real Estate Concierge (RE-1) constructor for a <strong>pure-realestate</strong> deployment (chairfill
     * off, so no {@code WaitlistClaimService}). The inbound webhook + STOP/opt-out still work; the YES path
     * is unreachable (realestate mode delegates every non-STOP body to the concierge router). Used by
     * {@code RealEstateAutoConfiguration}'s {@code @ConditionalOnMissingBean} fallback.
     */
    public InboundSmsService(IntegrationConnectionRepository connections,
                             ContactRepository contacts) {
        this.connections = connections;
        this.contacts = contacts;
        this.claimService = null;
    }

    /**
     * Wires the RE-1 grounded-concierge router (RE-1 §6.6). Invoked by {@code RealEstateAutoConfiguration}
     * when the realestate module is enabled; never called otherwise (the seam stays inert and ChairFill
     * is byte-identical). Idempotent / last-wins.
     */
    public void setConciergeRouter(@Nullable ConciergeInboundRouter conciergeRouter) {
        this.conciergeRouter = conciergeRouter;
    }

    /**
     * E2 — the generic inbound intent router (the reusable responder engine). Wired (via
     * {@link #setIntentRouter}) only when the {@code responder} module is loaded; null otherwise → the
     * {@code IGNORED} fallthrough below is <strong>byte-identical</strong> to before E2. The bean is
     * hand-constructed (not component-scanned), so this is a setter the responder auto-config invokes,
     * not field-{@code @Autowired} — exactly the {@link #conciergeRouter} seam. It is consulted ONLY on
     * the path that previously returned {@code IGNORED} (after STOP / YES / realestate had their chance),
     * so the shipped CF-3 / RE-1 inbound behavior + ITs are unaffected.
     */
    @Nullable
    private InboundIntentRouter intentRouter;

    /**
     * Wires the E2 generic responder router. Invoked by {@code ResponderAutoConfiguration} when the
     * responder module is enabled; never called otherwise (the seam stays inert). Idempotent / last-wins.
     */
    public void setIntentRouter(@Nullable InboundIntentRouter intentRouter) {
        this.intentRouter = intentRouter;
    }

    /** What an inbound SMS resolved to — for the controller to log. */
    public enum InboundOutcome {
        CLAIMED_WON, CLAIMED_LOST, NO_OPEN_OFFER, OPTED_OUT, IGNORED,
        // RE-1 realestate-mode outcomes.
        CONCIERGE_ANSWERED, CONCIERGE_HANDED_OFF, CONCIERGE_NO_LISTING,
        // RE-3 showing-booking outcomes (slots offered / a showing booked).
        CONCIERGE_BOOKING_OFFERED, CONCIERGE_BOOKED,
        // E2 generic-responder outcome — the InboundIntentRouter handled the message that would
        // otherwise have been IGNORED. An unhandled message still returns IGNORED (no behavior change).
        RESPONDER_HANDLED
    }

    /**
     * Entry point called by the controller. Verify-before-effect, then classify + route the body. Tenant
     * from the path only. Returns the outcome (the controller acknowledges Twilio with 200 regardless —
     * Twilio treats any 2xx as delivered).
     */
    public Mono<InboundOutcome> handleInboundSms(UUID tenantId, String signatureHeader, String fullUrl,
                                                 MultiValueMap<String, String> form) {
        return verifiedConnection(tenantId, signatureHeader, fullUrl, form)
                .flatMap(conn -> route(tenantId, form, smsModeOf(conn)));
    }

    /** The per-tenant routing mode from the verified connection's config (absent → ChairFill default). */
    @Nullable
    private static String smsModeOf(IntegrationConnection conn) {
        Map<String, String> config = conn.getConfig();
        return config == null ? null : config.get(SMS_MODE_KEY);
    }

    /**
     * Resolves the tenant's Twilio connection and verifies the signature — the ONLY place the signature
     * is checked (reused {@link TwilioRequestValidator}). Not-connected → {@code 4001}/404; signature
     * failure → {@code 4000}/401. Mirrors {@code TwilioVoicemailService.verifiedConnection} exactly.
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

    private Mono<InboundOutcome> route(UUID tenantId, MultiValueMap<String, String> form,
                                       @Nullable String smsMode) {
        String from = form == null ? null : form.getFirst("From");
        String to = form == null ? null : form.getFirst("To");
        String body = form == null ? null : form.getFirst("Body");
        String normalized = body == null ? "" : body.trim().toUpperCase();
        String firstWord = normalized.isEmpty() ? "" : normalized.split("\\s+")[0];

        if (from == null || from.isBlank()) {
            return Mono.just(InboundOutcome.IGNORED);
        }
        // STOP wins first, in EVERY mode (TCPA) — the unchanged shared opt-out (RE-1 §6.6).
        if (STOP_WORDS.contains(firstWord)) {
            return optOut(tenantId, from).thenReturn(InboundOutcome.OPTED_OUT);
        }
        // RE-1 seam: realestate mode + a wired router → the grounded concierge (single-turn Q&A).
        // The seam is consulted ONLY for non-STOP bodies and ONLY when smsMode=="realestate"; absent/
        // "chairfill" mode (or no router) falls straight through to the byte-identical ChairFill path.
        if (SMS_MODE_REALESTATE.equals(smsMode) && conciergeRouter != null) {
            return conciergeRouter.handle(tenantId, from, to, body)
                    .map(outcome -> switch (outcome) {
                        case ANSWERED -> InboundOutcome.CONCIERGE_ANSWERED;
                        case HANDED_OFF -> InboundOutcome.CONCIERGE_HANDED_OFF;
                        case BOOKING_OFFERED -> InboundOutcome.CONCIERGE_BOOKING_OFFERED;
                        case BOOKED -> InboundOutcome.CONCIERGE_BOOKED;
                        case NO_LISTING -> InboundOutcome.CONCIERGE_NO_LISTING;
                        case IGNORED -> InboundOutcome.IGNORED;
                    });
        }
        if (YES_WORDS.contains(firstWord) && claimService != null) {
            return claimService.handleAffirmative(tenantId, from)
                    .map(outcome -> switch (outcome) {
                        case WON -> InboundOutcome.CLAIMED_WON;
                        case LOST -> InboundOutcome.CLAIMED_LOST;
                        case NO_OPEN_OFFER -> InboundOutcome.NO_OPEN_OFFER;
                    });
        }
        // E2 generic-responder fallthrough (the ONE additive seam). When no existing branch matched
        // and the responder router is wired, delegate. The router returns HANDLED if it handled the
        // message (→ RESPONDER_HANDLED) or IGNORED otherwise (→ the byte-identical IGNORED below). A
        // tenant with no responder config makes the router return IGNORED, so default behavior is
        // unchanged. Null router (responder module off) → the original IGNORED path, byte-for-byte.
        if (intentRouter != null) {
            return intentRouter.handle(tenantId, from, to, body)
                    .map(o -> o == InboundIntentRouter.Outcome.HANDLED
                            ? InboundOutcome.RESPONDER_HANDLED
                            : InboundOutcome.IGNORED);
        }
        log.debug("CF-3 inbound SMS from {} for tenant {} not actionable: '{}'", from, tenantId, body);
        return Mono.just(InboundOutcome.IGNORED);
    }

    /**
     * STOP handling — sets the CF-2 {@value RiskTieredPreventionService#SMS_OPT_OUT_TAG} tag on every
     * contact matching the sender's phone for this tenant. Idempotent (a contact already carrying the
     * tag is re-saved with the same set). Runs under a synthetic context. Closes the CF-2 TCPA follow-up
     * (there was previously no inbound-STOP path).
     */
    private Mono<Void> optOut(UUID tenantId, String fromPhone) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        return contacts.findByTenantAndPhoneNumber(tenantId, fromPhone)
                .flatMap(contact -> {
                    Set<String> tags = contact.getTags() == null
                            ? new HashSet<>() : new HashSet<>(contact.getTags());
                    if (tags.contains(RiskTieredPreventionService.SMS_OPT_OUT_TAG)) {
                        return Mono.just(contact); // idempotent — already opted out
                    }
                    tags.add(RiskTieredPreventionService.SMS_OPT_OUT_TAG);
                    return contacts.save(contact.toBuilder().tags(tags).build());
                })
                .doOnNext(c -> log.info("CF-3: STOP honored — {} tagged sms-opt-out for tenant {}",
                        fromPhone, tenantId))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private static Map<String, String> flatten(MultiValueMap<String, String> form) {
        Map<String, String> flat = new HashMap<>();
        if (form != null) {
            form.forEach((k, v) -> flat.put(k, (v == null || v.isEmpty()) ? "" : v.get(0)));
        }
        return flat;
    }
}
