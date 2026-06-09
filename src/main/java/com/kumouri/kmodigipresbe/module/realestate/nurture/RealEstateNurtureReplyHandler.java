package com.kumouri.kmodigipresbe.module.realestate.nurture;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.service.nurture.NurtureReplyService;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * T1 (RE Database Goldmine) — the <strong>reply→book</strong> wiring (T1 directive #4): a positive reply
 * to an RE nurture cadence exits the enrollment and offers a showing via the booking-link SMS path.
 *
 * <h2>Rides the E2 responder seam (no router edit, no core change)</h2>
 * This is a {@link IntentHandler} the E2 {@code InboundIntentRouter} auto-discovers via its
 * {@code List<IntentHandler>} inject (the {@code DefaultHandoffIntentHandler} precedent). The router
 * dispatches an inbound SMS to the first handler whose {@link #supports(String, String)} returns true; we
 * claim a <strong>realestate-vertical</strong> conversation classified with a positive/booking intent and
 * delegate to the <strong>UNCHANGED</strong> {@link NurtureReplyService#handlePositiveReplyByPhone}, which
 * exits the contact's newest non-terminal enrollment ({@code REPLIED}) and best-effort sends the tenant's
 * booking link over SMS ({@code BOOKED}) — the RE booking-link pattern, <strong>no live Cal.com call</strong>.
 *
 * <h2>Inbound-mode note</h2>
 * The E2 router is consulted on {@code InboundSmsService}'s {@code IGNORED} fallthrough, reached only when
 * the tenant's {@code smsMode != "realestate"} (the grounded-concierge seam owns realestate-mode bodies).
 * So an RE-nurture deployment leaves {@code smsMode} unset and drives the reply→book path purely off the
 * tenant's {@code ResponderConfig(vertical="realestate")} + this handler.
 *
 * <h2>Best-effort</h2>
 * {@code handlePositiveReplyByPhone} 4310s when the sender has no active enrollment — we swallow that to
 * {@link HandlerResult#ignored()} (this handler simply didn't apply; the router's default-handoff or the
 * IGNORED no-op takes over). On success we return {@link HandlerResult#handledNoReply()} because
 * {@code NurtureReplyService} already sent the booking-link SMS itself (returning a {@code replyText}
 * would double-text). Never throws.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateNurtureAutoConfiguration} (so it exists only
 * when realestate + nurture are both on); registered into the responder registry purely by being a bean.
 */
@Slf4j
public class RealEstateNurtureReplyHandler implements IntentHandler {

    public static final String KEY = "realestate-nurture-reply";

    /** The vertical this handler claims (matches the tenant's {@code ResponderConfig.vertical}). */
    public static final String VERTICAL = "realestate";

    /**
     * The classified intent names that mean "yes, re-engage me / book" — the tenant's
     * {@code ResponderConfig.intents} should name one of these for the reply→book path to fire. Matched
     * case-insensitively.
     */
    static final Set<String> POSITIVE_INTENTS = Set.of(
            "POSITIVE_REPLY", "INTERESTED", "BOOK", "BOOKING", "YES", "SCHEDULE", "SHOWING");

    private final NurtureReplyService nurtureReplyService;

    public RealEstateNurtureReplyHandler(NurtureReplyService nurtureReplyService) {
        this.nurtureReplyService = nurtureReplyService;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean supports(String vertical, String intent) {
        return VERTICAL.equalsIgnoreCase(vertical)
                && intent != null
                && POSITIVE_INTENTS.contains(intent.trim().toUpperCase());
    }

    @Override
    public Mono<HandlerResult> handle(HandlerContext ctx) {
        return nurtureReplyService.handlePositiveReplyByPhone(ctx.tenantId(), ctx.fromPhone())
                .doOnNext(enr -> log.info(
                        "RE-nurture reply→book: enrollment {} for {} advanced to {} (booking link sent by "
                                + "NurtureReplyService)", enr.getId(), ctx.fromPhone(), enr.getStatus()))
                // NurtureReplyService already texted the booking link — no router reply (avoid double-text).
                .map(enr -> HandlerResult.handledNoReply())
                // 4310 = no active enrollment for this sender → this handler simply didn't apply.
                .onErrorResume(DigiPresBeException.class, e -> {
                    if (e.getErrorCode() == 4310) {
                        log.debug("RE-nurture reply→book: no active enrollment for {} (4310) — ignored",
                                ctx.fromPhone());
                        return Mono.just(HandlerResult.ignored());
                    }
                    log.warn("RE-nurture reply→book failed (best-effort, ignored) for {}: {}",
                            ctx.fromPhone(), e.toString());
                    return Mono.just(HandlerResult.ignored());
                })
                .onErrorResume(e -> {
                    log.warn("RE-nurture reply→book unexpected error (best-effort, ignored) for {}: {}",
                            ctx.fromPhone(), e.toString());
                    return Mono.just(HandlerResult.ignored());
                });
    }
}
