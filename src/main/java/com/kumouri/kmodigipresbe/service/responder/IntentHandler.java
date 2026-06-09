package com.kumouri.kmodigipresbe.service.responder;

import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * E2 — the plug point for vertical responder behavior. A later consumer (Real-Estate "Midnight
 * Responder", Health "Switchboard AI" logistics intents, Home "Instant Callback") registers a vertical
 * handler purely by contributing an {@code IntentHandler} {@code @Bean} — the {@link InboundIntentRouter}
 * auto-discovers every {@code IntentHandler} bean via a {@code List<IntentHandler>} inject and dispatches
 * to the first whose {@link #supports(String, String)} returns true. <strong>No router edit is ever
 * needed to add a handler</strong> (design directive #2).
 *
 * <p>A handler returns a {@link HandlerResult} describing the optional reply text + whether it handled
 * the message + whether the conversation should be considered complete. The router (not the handler)
 * owns the consent gate, the reply cap, the actual SMS send, the conversation-state persistence, and the
 * advisory events — so a handler stays small and side-effect-light (it may, however, do its own domain
 * writes, e.g. create a Deal or an Activity, since it runs under the synthetic tenant context).
 *
 * <p>The built-in {@link DefaultHandoffIntentHandler} (key {@code "default-handoff"}) is the safety net
 * used when no specific handler matched OR the intent is {@code UNKNOWN}; it has the lowest precedence
 * (the router only falls back to it). A specific handler's {@link #supports} should NOT match the
 * default-handoff key.
 */
public interface IntentHandler {

    /** A stable unique key for this handler (e.g. {@code "realestate-concierge"}, {@code "default-handoff"}). */
    String key();

    /**
     * Whether this handler should process the given {@code vertical} + classified {@code intent}. The
     * router consults handlers in registration order and dispatches to the first match; the built-in
     * default-handoff is excluded from this first pass and used only as the fallback.
     */
    boolean supports(String vertical, String intent);

    /** Process one inbound message; the router applies the consent/cap gate + send + persistence around this. */
    Mono<HandlerResult> handle(HandlerContext ctx);

    /**
     * The per-message context handed to a handler. Carries the resolved tenant, the inbound envelope, the
     * classification, and the (already found-or-created) conversation state so a handler can read/accumulate
     * multi-turn slots.
     *
     * @param tenantId       the resolved tenant (from the URL path → the synthetic context)
     * @param fromPhone      the sender's phone (E.164)
     * @param toPhone        the business number the message was sent to (nullable)
     * @param body           the raw inbound message body
     * @param classification the classifier result (may be {@link IntentClassification#unknown()})
     * @param conversation   the live conversation-state row for {@code (tenantId, fromPhone)}
     */
    record HandlerContext(UUID tenantId, String fromPhone, String toPhone, String body,
                          IntentClassification classification, ConversationState conversation) {
    }

    /**
     * What a handler decided. {@code replyText} (nullable) is the SMS the router should send back to the
     * sender (subject to the consent gate + reply cap); {@code handled} is true when the handler took
     * meaningful action (drives the {@code HANDLED} vs {@code IGNORED} router outcome);
     * {@code exitConversation} hints that the thread is complete (reserved for future use — the router
     * does not delete the row, the TTL reaps it).
     */
    record HandlerResult(String replyText, boolean handled, boolean exitConversation) {

        /** A reply + handled, conversation continues. */
        public static HandlerResult reply(String text) {
            return new HandlerResult(text, true, false);
        }

        /** Handled with no reply (e.g. a silent log/notify). */
        public static HandlerResult handledNoReply() {
            return new HandlerResult(null, true, false);
        }

        /** Not handled — the router treats this as IGNORED. */
        public static HandlerResult ignored() {
            return new HandlerResult(null, false, false);
        }
    }
}
