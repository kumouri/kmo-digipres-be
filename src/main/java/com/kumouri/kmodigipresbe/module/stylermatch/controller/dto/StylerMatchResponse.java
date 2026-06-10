package com.kumouri.kmodigipresbe.module.stylermatch.controller.dto;

import com.kumouri.kmodigipresbe.module.stylermatch.model.RankedMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatchStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — the client-facing response to a match submission (and the office inbox
 * detail). Surfaces the echoed request + the ranked stylist board (each with an explained rationale +
 * per-component breakdown + the central "salon will confirm" guardrail), the confidence, and the
 * lifecycle status — never the raw entity / tenant-internal fields. The stylist-side twin of the T9
 * {@code StyleConsultResponse}.
 *
 * @param matchId               the persisted {@code StylerMatch} id (the accept endpoint takes it)
 * @param serviceMenuItemId     the requested service id, nullable
 * @param serviceMenuItemName   the requested service name snapshot, nullable
 * @param styleCategory         the requested style category, nullable
 * @param slotStart             the requested slot start, nullable
 * @param slotEnd               the requested slot end, nullable
 * @param confidence            the match confidence in [0,1]
 * @param rankedMatches         the ranked stylists, best-fit first
 * @param status                NEW or BOOKED
 * @param selectedStaffMemberId the stylist booked on accept (null until booked)
 * @param selectedRank          the 1-based rank that was booked (null until booked)
 * @param bookingId             the salon Booking created on accept (null until booked)
 */
public record StylerMatchResponse(
        UUID matchId,
        String serviceMenuItemId,
        String serviceMenuItemName,
        String styleCategory,
        Instant slotStart,
        Instant slotEnd,
        double confidence,
        List<RankedMatch> rankedMatches,
        StylerMatchStatus status,
        UUID selectedStaffMemberId,
        Integer selectedRank,
        UUID bookingId) {

    /** Project a persisted {@link StylerMatch} into the client/office response shape. */
    public static StylerMatchResponse from(StylerMatch m) {
        return new StylerMatchResponse(
                m.getId(),
                m.getServiceMenuItemId(),
                m.getServiceMenuItemName(),
                m.getStyleCategory(),
                m.getSlotStart(),
                m.getSlotEnd(),
                m.getConfidence(),
                m.getRankedMatches() == null ? List.of() : m.getRankedMatches(),
                m.getStatus(),
                m.getSelectedStaffMemberId(),
                m.getSelectedRank(),
                m.getBookingId());
    }
}
