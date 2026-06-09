package com.kumouri.kmodigipresbe.module.realestate.responder;

import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — the off-listing / unknown-intent <strong>handoff delegation</strong>
 * seam. When the grounded concierge cannot help an inbound buyer SMS — no listing matched
 * ({@code NO_LISTING}) or the model strictly declined to ground an answer ({@code HANDOFF}) — the
 * {@code ConciergeInboundRouter} optionally delegates to this, which routes the message through the E2
 * responder default-handoff path (staff notify + a generic "a team member will follow up" reply) instead
 * of dying silently.
 *
 * <p>Best-effort by contract: an implementation must never throw and must swallow its own send failures
 * (the concierge router is webhook-reachable and must never 500). {@code delegate(...)} returns {@code true}
 * when it actioned the handoff (sent a reply / notified staff), {@code false} when it did not (e.g. the
 * per-tenant policy declined, or no notify target) — the router uses this only for logging, the concierge
 * outcome is unchanged either way.
 *
 * <h2>Why a {@link Reason}</h2>
 * The off-listing ({@code NO_LISTING}) case always delegates when the handoff is wired; the strict
 * {@code HANDOFF} case delegates only when the per-tenant {@code MidnightResponderConfig
 * .delegateHandoffToResponder} flag is set. Carrying the reason lets the <strong>delegate</strong> own that
 * per-tenant policy check (it holds the config repo), keeping the concierge-router seam a dead-simple
 * "delegate this unhelpable message, here's why" — the router needs no T3 config dependency.
 *
 * <p>The implementation ({@link ResponderHandoffDelegateImpl}) wraps the E2
 * {@code DefaultHandoffIntentHandler} — reusing its vetted, static, fair-housing-safe handoff template +
 * its per-tenant staff-notify (NOT hardcoded) — so T3 adds no new handoff copy and no new notify path.
 */
public interface ResponderHandoffDelegate {

    /** Why the concierge could not help — drives the delegate's per-tenant policy gate. */
    enum Reason {
        /** No listing could be resolved for the inbound (the disambiguation case). Always delegates. */
        NO_LISTING,
        /** The model strictly declined to ground an answer. Delegates iff the per-tenant flag is set. */
        HANDOFF
    }

    /**
     * Best-effort handoff of an inbound buyer message the concierge could not ground.
     *
     * @param tenantId the resolved tenant (the concierge router's synthetic context tenant)
     * @param from     the buyer's phone (E.164)
     * @param to       the business number the message was sent to (nullable)
     * @param body     the raw inbound message body
     * @param reason   why the concierge could not help (gates the per-tenant policy)
     * @return {@code true} if the handoff was actioned (reply sent / staff notified), else {@code false}
     * (declined by policy, or a swallowed failure); never errors.
     */
    Mono<Boolean> delegate(UUID tenantId, String from, String to, String body, Reason reason);
}
