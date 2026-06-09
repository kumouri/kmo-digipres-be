package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistClaimEngine;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * T7 (Health "RescheduleFlow") — the inbound-YES wiring: an E2 {@link IntentHandler} that turns an offered
 * patient's affirmative reply into an atomic slot claim via the E4 {@link WaitlistClaimEngine}. The
 * {@code FrontDeskNurtureReplyHandler} (T2) / {@code LogisticsIntentHandler} (T4) precedent — a
 * frontdesk-vertical handler the E2 {@code InboundIntentRouter} auto-discovers via its
 * {@code List<IntentHandler>} inject (<strong>no router edit, no {@code InboundSmsService} edit</strong>).
 *
 * <h2>Why the E2 responder seam (not a new inbound-SMS route)</h2>
 * For a frontdesk/health tenant ChairFill is off, so {@code InboundSmsService.claimService == null} and a
 * YES word falls through {@code InboundSmsService}'s {@code IGNORED} path to the E2 responder router (the RE
 * / T2 precedent). So a RescheduleFlow deployment leaves the Twilio {@code smsMode} unset and drives the YES
 * claim purely off the tenant's {@code ResponderConfig(vertical="health-reschedule")} naming an affirmative
 * intent + this handler. The shipped {@code InboundSmsService} + E2 router cores stay byte-equivalent.
 *
 * <h2>STOP / opt-out honored upstream</h2>
 * STOP is handled first in {@code InboundSmsService} (the unchanged TCPA opt-out), and the E2 router itself
 * gates an opted-out sender to {@code IGNORED} before any handler runs — so this handler never claims for an
 * opted-out contact (the {@code WaitlistClaimEngine} additionally only resolves OFFERED offers, which the
 * gap-fill never sent to an opted-out contact).
 *
 * <h2>Claim → the engine owns the SMS</h2>
 * On an affirmative intent it calls {@link WaitlistClaimEngine#claim} (the atomic first-YES findAndModify;
 * exactly-one-winner; the winner's {@link FrontDeskSlotMaterializer} creates the PHI-free Appointment). The
 * engine already sends the confirmation (winner) / apology (loser) SMS, so this returns
 * {@link HandlerResult#handledNoReply()} — returning a {@code replyText} would double-text. A WON records a
 * CLAIM fill-funnel row (best-effort). {@code NO_OPEN_OFFER} (the sender had no live offer — e.g. a stray
 * YES) is {@link HandlerResult#ignored()} so the router can fall through to its default handoff. Never throws.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RescheduleFlowAutoConfiguration} (so it exists only when
 * frontdesk + waitlist are both on); registered into the responder registry purely by being a bean.
 */
@Slf4j
public class RescheduleWaitlistIntentHandler implements IntentHandler {

    public static final String KEY = "health-reschedule-waitlist";

    /** The vertical this handler claims (matches the tenant's {@code ResponderConfig.vertical}). */
    public static final String VERTICAL = "health-reschedule";

    /**
     * The classified intent names that mean "yes, I'll take the slot" — the tenant's
     * {@code ResponderConfig.intents} should name one of these for the claim path to fire. Matched
     * case-insensitively. (The classifier maps a patient's "YES"/"sure"/"I'll take it" to one of these.)
     */
    static final Set<String> AFFIRMATIVE_INTENTS = Set.of(
            "YES", "CONFIRM", "ACCEPT", "ACCEPT_SLOT", "CLAIM", "CLAIM_SLOT", "BOOK", "BOOKING",
            "TAKE_SLOT", "AFFIRMATIVE", "POSITIVE_REPLY");

    private final WaitlistClaimEngine claimEngine;
    private final RescheduleAnalyticsService analytics;

    public RescheduleWaitlistIntentHandler(WaitlistClaimEngine claimEngine,
                                           RescheduleAnalyticsService analytics) {
        this.claimEngine = claimEngine;
        this.analytics = analytics;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean supports(String vertical, String intent) {
        return VERTICAL.equalsIgnoreCase(vertical)
                && intent != null
                && AFFIRMATIVE_INTENTS.contains(intent.trim().toUpperCase());
    }

    @Override
    public Mono<HandlerResult> handle(HandlerContext ctx) {
        return claimEngine.claim(ctx.tenantId(), ctx.fromPhone())
                .flatMap(outcome -> {
                    log.info("RescheduleFlow inbound YES from {} (tenant {}) -> {}",
                            ctx.fromPhone(), ctx.tenantId(), outcome);
                    switch (outcome) {
                        case WON:
                            // The engine sent the confirmation SMS itself; record the CLAIM stage.
                            return analytics.record(ctx.tenantId(), RescheduleFillEvent.CLAIM)
                                    .thenReturn(HandlerResult.handledNoReply());
                        case LOST:
                            // The engine sent the apology SMS itself; handled, no router reply.
                            return Mono.just(HandlerResult.handledNoReply());
                        case NO_OPEN_OFFER:
                        default:
                            // No live offer for this sender — let the router fall through to its default.
                            return Mono.just(HandlerResult.ignored());
                    }
                })
                .onErrorResume(e -> {
                    log.warn("RescheduleFlow inbound-YES claim failed (best-effort, ignored) for {}: {}",
                            ctx.fromPhone(), e.toString());
                    return Mono.just(HandlerResult.ignored());
                });
    }
}
