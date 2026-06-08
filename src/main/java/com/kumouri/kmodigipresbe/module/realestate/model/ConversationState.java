package com.kumouri.kmodigipresbe.module.realestate.model;

/**
 * Real Estate Concierge — the lifecycle state of a {@link ConciergeConversation} (RE-1 §4 decision 2).
 *
 * <p><strong>RE-1 reaches only {@link #ASKING} and {@link #HANDED_OFF}.</strong> {@link #QUALIFYING} is
 * reached by RE-2 (qualification + lead scoring); the booking states {@link #OFFERING_SLOTS} and
 * {@link #BOOKED} are reached by RE-3 (showing booking over SMS). The {@link #OPTED_OUT} state is declared
 * for the TCPA opt-out. The whole enum was declared up front so the persisted conversation shape is
 * forward-compatible across phases without a model migration.
 */
public enum ConversationState {

    /** Default — the buyer is asking disclosure questions; each inbound is answered grounded or HANDOFF. */
    ASKING,

    /** RE-2 — the concierge is extracting budget/timeline/financing into a Deal. */
    QUALIFYING,

    /**
     * RE-3 — the concierge has offered showing slots over SMS and is awaiting the buyer's pick. The
     * candidate slots are persisted on the conversation ({@code offeredSlots}) so the next inbound turn
     * (a number/slot pick) resolves to the booking handler rather than the grounded-answer path.
     */
    OFFERING_SLOTS,

    /** RE-3 — a showing {@code Meeting} has been written; the booking is complete (terminal for the flow). */
    BOOKED,

    /** The answer was not in the disclosures (or escalation warranted) → the agent was looped in. */
    HANDED_OFF,

    /** The buyer texted STOP — TCPA opt-out (also sets the shared {@code sms-opt-out} contact tag). */
    OPTED_OUT
}
