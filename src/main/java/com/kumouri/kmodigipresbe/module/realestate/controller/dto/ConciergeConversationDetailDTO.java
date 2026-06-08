package com.kumouri.kmodigipresbe.module.realestate.controller.dto;

import com.kumouri.kmodigipresbe.module.realestate.model.BuyerQualification;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-5a) — the full read of one concierge conversation
 * ({@code GET /realestate/conversations/{id}}), backing the RE-5b transcript + citation viewer + lead
 * panel. A lean projection of a {@link ConciergeConversation}: the ordered {@link TurnDTO} transcript
 * (each assistant turn carrying its grounding {@link CitationDTO}s), the accumulated
 * {@link QualificationDTO}, and the linked {@code dealId} + resolved {@code leadTier}.
 *
 * <p>Pure read, no document leakage: the embedded {@code offeredSlots} (an RE-3 booking-flow internal),
 * the {@code @Version}, and the correlation-internal {@code buyerPhone} are surfaced deliberately
 * ({@code buyerPhone} is useful agent context on the panel; the slots/version are omitted). The
 * {@code leadTier} is resolved by the controller off the buyer Contact's {@code leadScore} (the same
 * HOT/WARM/COLD the RE-2 hot-handoff keys on), or null when the buyer is unscored / not yet materialized.
 *
 * @param id            the ConciergeConversation id
 * @param listingId     the listing this thread is about
 * @param contactId     the buyer Contact, or null until RE-2 resolves/creates it
 * @param dealId        the materialized concierge Deal, or null until RE-2 materializes it
 * @param meetingId     the booked showing Meeting, or null until RE-3 books one
 * @param buyerPhone    the buyer's E.164 phone (the per-listing correlation key) — agent context
 * @param state         the conversation lifecycle state
 * @param leadTier      the buyer's lead tier (HOT/WARM/COLD), or null when unscored / no contact
 * @param optedOut      whether the buyer texted STOP (TCPA opt-out)
 * @param qualification the accumulated buyer qualification, or null until RE-2 extracts any
 * @param turns         the transcript, oldest first, each assistant turn carrying its citations
 * @param lastInboundAt when the most recent inbound buyer text landed
 * @param createdAt     when the thread was created
 * @param updatedAt     when the thread was last persisted
 */
public record ConciergeConversationDetailDTO(
        UUID id,
        UUID listingId,
        UUID contactId,
        UUID dealId,
        UUID meetingId,
        String buyerPhone,
        ConversationState state,
        String leadTier,
        boolean optedOut,
        QualificationDTO qualification,
        List<TurnDTO> turns,
        Instant lastInboundAt,
        Instant createdAt,
        Instant updatedAt) {

    public static ConciergeConversationDetailDTO from(ConciergeConversation c, String leadTier) {
        List<TurnDTO> turnDtos = (c.getTurns() == null ? List.<ConciergeTurn>of() : c.getTurns())
                .stream()
                .map(TurnDTO::from)
                .toList();
        return new ConciergeConversationDetailDTO(
                c.getId(),
                c.getListingId(),
                c.getContactId(),
                c.getDealId(),
                c.getMeetingId(),
                c.getBuyerPhone(),
                c.getState(),
                leadTier,
                c.isOptedOut(),
                QualificationDTO.from(c.getQualification()),
                turnDtos,
                c.getLastInboundAt(),
                c.getCreatedAt(),
                c.getUpdatedAt());
    }

    /**
     * One turn of the transcript. A buyer turn carries the question ({@code role=BUYER}, empty
     * {@code citations}); an assistant turn carries the grounded answer (or the handoff line, with
     * {@code handoff=true} + empty {@code citations}) and its {@link CitationDTO}s.
     *
     * @param role      BUYER or ASSISTANT
     * @param body      the message text
     * @param at        when the turn was recorded
     * @param handoff   true on the assistant turn that handed off to the agent (no grounded answer)
     * @param citations the disclosure citations the assistant answer was grounded in (empty otherwise)
     */
    public record TurnDTO(
            ConciergeTurn.Role role,
            String body,
            Instant at,
            boolean handoff,
            List<CitationDTO> citations) {

        public static TurnDTO from(ConciergeTurn t) {
            List<CitationDTO> cites = (t.getCitations() == null
                    ? List.<ConciergeTurn.TurnCitation>of() : t.getCitations())
                    .stream()
                    .map(CitationDTO::from)
                    .toList();
            return new TurnDTO(t.getRole(), t.getBody(), t.getAt(), t.isHandoff(), cites);
        }
    }

    /**
     * One cited disclosure surfaced on an assistant turn — the RE-1 §6.5 citation contract. The RE-5b
     * citation viewer renders "Answered from: {@code disclosureType} — '{@code contentPreview}'".
     *
     * @param disclosureId   the cited {@code ListingDisclosure} id
     * @param disclosureType the disclosure category
     * @param contentPreview the disclosure text preview (≤500 chars)
     * @param score          the vector similarity score
     */
    public record CitationDTO(
            UUID disclosureId,
            String disclosureType,
            String contentPreview,
            double score) {

        public static CitationDTO from(ConciergeTurn.TurnCitation c) {
            return new CitationDTO(
                    c.getDisclosureId(),
                    c.getDisclosureType(),
                    c.getContentPreview(),
                    c.getScore());
        }
    }

    /**
     * The accumulated buyer qualification (RE-2). Every field is best-effort / nullable; fields
     * accumulate turn-to-turn and feed the materialized {@code Deal} the nightly scorer tiers.
     *
     * @param budget          the buyer's budget / target price (USD), or null until revealed
     * @param timeline        free-text timeline phrase, or null
     * @param financing       free-text financing posture, or null
     * @param preApproved     true when the buyer indicated pre-approval; null when unknown
     * @param intent          BUY or SELL, or null when unknown
     * @param dealMaterialized true once a Deal has been materialized from the qualification
     */
    public record QualificationDTO(
            BigDecimal budget,
            String timeline,
            String financing,
            Boolean preApproved,
            BuyerQualification.Intent intent,
            boolean dealMaterialized) {

        /** Null-safe: a conversation with no qualification yet projects to a null QualificationDTO. */
        public static QualificationDTO from(BuyerQualification q) {
            if (q == null) {
                return null;
            }
            return new QualificationDTO(
                    q.getBudget(),
                    q.getTimeline(),
                    q.getFinancing(),
                    q.getPreApproved(),
                    q.getIntent(),
                    q.isDealMaterialized());
        }
    }
}
