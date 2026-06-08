package com.kumouri.kmodigipresbe.module.realestate.model;

/**
 * Real Estate Concierge — the lifecycle state of a {@link ConciergeConversation} (RE-1 §4 decision 2).
 *
 * <p><strong>RE-1 reaches only {@link #ASKING} and {@link #HANDED_OFF}.</strong> The qualification
 * ({@link #QUALIFYING}), slot-offering ({@link #OFFERING_SLOTS}), booked ({@link #BOOKED}), and opted-out
 * ({@link #OPTED_OUT}) states are declared now so the persisted conversation shape is forward-compatible
 * with RE-2 (qualification + lead scoring) and RE-3 (Cal.com showing booking) without a model migration —
 * but RE-1 never transitions into them. RE-1 is a single-turn grounded Q&A with persisted state.
 */
public enum ConversationState {

    /** Default — the buyer is asking disclosure questions; each inbound is answered grounded or HANDOFF. */
    ASKING,

    /** RE-2 — the concierge is extracting budget/timeline/financing into a Deal. (Unreached in RE-1.) */
    QUALIFYING,

    /** RE-3 — the concierge has offered showing slots and is awaiting the buyer's pick. (Unreached in RE-1.) */
    OFFERING_SLOTS,

    /** RE-3 — a showing has been booked. (Unreached in RE-1.) */
    BOOKED,

    /** The answer was not in the disclosures (or escalation warranted) → the agent was looped in. */
    HANDED_OFF,

    /** The buyer texted STOP — TCPA opt-out (also sets the shared {@code sms-opt-out} contact tag). */
    OPTED_OUT
}
