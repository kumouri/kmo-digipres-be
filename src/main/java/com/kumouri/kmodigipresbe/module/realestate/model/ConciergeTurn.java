package com.kumouri.kmodigipresbe.module.realestate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — one turn in a {@link ConciergeConversation}, embedded in the
 * conversation document. The buyer's inbound SMS and the concierge's grounded reply are each a turn.
 *
 * <p>Assistant turns persist their {@link #citations} (the disclosure line(s) the answer was grounded in)
 * so the agent-facing transcript shows the proof-of-grounding inline without a re-query (RE-1 §6.2 / §6.5).
 * A {@code HANDOFF} reply carries an empty citation list (nothing grounded it) and {@link #handoff}=true.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConciergeTurn {

    public enum Role { BUYER, ASSISTANT }

    private Role role;

    /** The message text (the buyer's question, or the concierge's answer / handoff line). */
    private String body;

    private Instant at;

    /** True on the assistant turn that handed off to the agent (no grounded answer). */
    @Builder.Default
    private boolean handoff = false;

    /** Citations the answer was grounded in (assistant turns only); empty for buyer turns and handoffs. */
    @Builder.Default
    private List<TurnCitation> citations = List.of();

    /**
     * One cited disclosure surfaced on an assistant turn — the RE-1 §6.5 citation contract carried
     * through from the RAG {@code AskResult.Citation} ({@code sourceType="ListingDisclosure"}, {@code
     * sourceId}, {@code contentPreview}, {@code score}). The FE citation viewer (RE-5) renders
     * "Answered from: {@code disclosureType} — '{@code contentPreview}'".
     */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TurnCitation {
        /** The {@link ListingDisclosure} id (the vector's {@code sourceId}). */
        private UUID disclosureId;
        /** The disclosure category (from the vector's {@code disclosureType} metadata). */
        private String disclosureType;
        /** The disclosure text preview (the vector's {@code contentPreview}, ≤500 chars). */
        private String contentPreview;
        /** The vector similarity score. */
        private double score;
    }
}
