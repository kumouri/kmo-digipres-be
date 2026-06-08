package com.kumouri.kmodigipresbe.module.realestate.controller.dto;

import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-5a) — one row in the staff-facing concierge conversation list
 * ({@code GET /realestate/conversations}). A lean projection of a {@link ConciergeConversation}: the
 * fields an agent needs to triage the concierge's threads at a glance — which listing, which buyer,
 * what state the thread is in, how warm the resulting lead is, how much traffic it's seen, and when it
 * last moved — without streaming the full transcript (that is the {@link ConciergeConversationDetailDTO}
 * detail call).
 *
 * <p>Like {@code WaitlistBoardEntryDTO}/{@code MissedCallInboxItemDTO}, this is a flat projection — the
 * {@code listingId}/{@code contactId}/{@code dealId} are surfaced as ids the FE resolves against the
 * listings/contacts/deals it already loads. The {@code leadTier} is the one cross-collection enrichment
 * (HOT/WARM/COLD read off the buyer Contact's {@code leadScore}, the same tier the RE-2 hot-handoff
 * keys on), or null when the buyer is unscored / not yet materialized — see
 * {@link ConciergeConversationDetailDTO} for how the tier is resolved.
 *
 * @param id             the ConciergeConversation id (the detail-call key)
 * @param listingId      the listing this thread is about
 * @param contactId      the buyer Contact, or null until RE-2 resolves/creates it
 * @param dealId         the materialized concierge Deal, or null until RE-2 materializes it
 * @param state          the conversation lifecycle state (ASKING/QUALIFYING/OFFERING_SLOTS/BOOKED/…)
 * @param leadTier       the buyer's lead tier (HOT/WARM/COLD), or null when unscored / no contact
 * @param turnCount      the number of turns in the thread (buyer + assistant)
 * @param optedOut       whether the buyer texted STOP (TCPA opt-out)
 * @param lastActivityAt when the thread last moved (newest-first ordering key) — the last inbound, or
 *                       the persisted {@code updatedAt} when no inbound has landed yet
 */
public record ConciergeConversationSummaryDTO(
        UUID id,
        UUID listingId,
        UUID contactId,
        UUID dealId,
        ConversationState state,
        String leadTier,
        int turnCount,
        boolean optedOut,
        Instant lastActivityAt) {

    /**
     * Project a conversation to a list row, stamping the {@code leadTier} resolved (cross-collection)
     * by the controller. {@code lastActivityAt} prefers {@link ConciergeConversation#getLastInboundAt()}
     * (the recency key the thread is ordered by) and falls back to {@code updatedAt} for a freshly
     * created thread that has not yet recorded an inbound.
     */
    public static ConciergeConversationSummaryDTO from(ConciergeConversation c, String leadTier) {
        List<?> turns = c.getTurns();
        Instant lastActivity = c.getLastInboundAt() != null ? c.getLastInboundAt() : c.getUpdatedAt();
        return new ConciergeConversationSummaryDTO(
                c.getId(),
                c.getListingId(),
                c.getContactId(),
                c.getDealId(),
                c.getState(),
                leadTier,
                turns == null ? 0 : turns.size(),
                c.isOptedOut(),
                lastActivity);
    }
}
