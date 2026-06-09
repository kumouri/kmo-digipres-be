package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.service.nurture.NurtureReplyService;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * T2 (Health "RevenueRevive") — the <strong>reply→rebook</strong> wiring (T2 directive #3, the health twin
 * of T1's {@code RealEstateNurtureReplyHandler}): a positive reply to a health nurture cadence exits the
 * enrollment and offers a rebook via the booking-link SMS path.
 *
 * <h2>Rides the E2 responder seam (no router edit, no core change)</h2>
 * This is a {@link IntentHandler} the E2 {@code InboundIntentRouter} auto-discovers via its
 * {@code List<IntentHandler>} inject (the {@code DefaultHandoffIntentHandler}/{@code RealEstateNurtureReplyHandler}
 * precedent — the {@code IntentHandler} contract itself names "Health 'Switchboard AI' logistics intents" as
 * an intended consumer). The router dispatches an inbound SMS to the first handler whose
 * {@link #supports(String, String)} returns true; we claim a <strong>frontdesk-vertical</strong> conversation
 * classified with a positive/booking intent and delegate to the <strong>UNCHANGED</strong>
 * {@link NurtureReplyService#handlePositiveReplyByPhone}, which exits the contact's newest non-terminal
 * enrollment ({@code REPLIED}) and best-effort sends the practice's booking link over SMS ({@code BOOKED}) —
 * the rebook-link pattern, <strong>no live Cal.com call</strong>.
 *
 * <h2>Inbound-mode note</h2>
 * The frontdesk module has no inbound-SMS {@code smsMode} of its own, so an inbound positive reply falls
 * through {@code InboundSmsService}'s {@code IGNORED} path to the E2 responder seam (the RE precedent). So a
 * health-nurture deployment leaves {@code smsMode} unset and drives reply→rebook purely off the tenant's
 * {@code ResponderConfig(vertical="frontdesk")} + this handler.
 *
 * <h2>Best-effort + PHI-free</h2>
 * {@code handlePositiveReplyByPhone} 4310s when the sender has no active enrollment — we swallow that to
 * {@link HandlerResult#ignored()} (this handler simply didn't apply). On success we return
 * {@link HandlerResult#handledNoReply()} because {@code NurtureReplyService} already sent the booking-link SMS
 * itself (returning a {@code replyText} would double-text). The booking-link message is a generic "let's find
 * a time" template (no procedure/provider — PHI-free). Never throws.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code FrontDeskNurtureAutoConfiguration} (so it exists only when
 * frontdesk + nurture are both on); registered into the responder registry purely by being a bean.
 */
@Slf4j
public class FrontDeskNurtureReplyHandler implements IntentHandler {

    public static final String KEY = "frontdesk-nurture-reply";

    /** The vertical this handler claims (matches the tenant's {@code ResponderConfig.vertical}). */
    public static final String VERTICAL = "frontdesk";

    /**
     * The classified intent names that mean "yes, re-engage me / rebook" — the tenant's
     * {@code ResponderConfig.intents} should name one of these for the reply→rebook path to fire. Matched
     * case-insensitively.
     */
    static final Set<String> POSITIVE_INTENTS = Set.of(
            "POSITIVE_REPLY", "INTERESTED", "BOOK", "BOOKING", "YES", "SCHEDULE", "REBOOK", "RESCHEDULE");

    private final NurtureReplyService nurtureReplyService;

    public FrontDeskNurtureReplyHandler(NurtureReplyService nurtureReplyService) {
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
                        "Health-nurture reply→rebook: enrollment {} for {} advanced to {} (booking link sent "
                                + "by NurtureReplyService)", enr.getId(), ctx.fromPhone(), enr.getStatus()))
                // NurtureReplyService already texted the booking link — no router reply (avoid double-text).
                .map(enr -> HandlerResult.handledNoReply())
                // 4310 = no active enrollment for this sender → this handler simply didn't apply.
                .onErrorResume(DigiPresBeException.class, e -> {
                    if (e.getErrorCode() == 4310) {
                        log.debug("Health-nurture reply→rebook: no active enrollment for {} (4310) — ignored",
                                ctx.fromPhone());
                        return Mono.just(HandlerResult.ignored());
                    }
                    log.warn("Health-nurture reply→rebook failed (best-effort, ignored) for {}: {}",
                            ctx.fromPhone(), e.toString());
                    return Mono.just(HandlerResult.ignored());
                })
                .onErrorResume(e -> {
                    log.warn("Health-nurture reply→rebook unexpected error (best-effort, ignored) for {}: {}",
                            ctx.fromPhone(), e.toString());
                    return Mono.just(HandlerResult.ignored());
                });
    }
}
