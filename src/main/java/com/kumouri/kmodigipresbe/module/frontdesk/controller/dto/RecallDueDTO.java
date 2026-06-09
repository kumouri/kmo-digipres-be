package com.kumouri.kmodigipresbe.module.frontdesk.controller.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * FrontDesk IQ (FD-5a) — one recall-due contact on the staff-facing recall board
 * ({@code GET /frontdesk/recall}). A lean projection of a lapsed patient: most-recent visit older than
 * the recall window AND no upcoming appointment — the same lapsed-contact selector FD-2's
 * {@code RecallDetectorJob} sweeps, surfaced as a read so the FD-5 recall-board FE can show who is due
 * for recall/recare and whether the nightly sweep has already nudged them this period.
 *
 * <h2>PHI-free (fence F1/F3)</h2>
 * Every field is scheduling-logistics metadata: the contact id, a display name, the metadata
 * last-visit timestamp, the derived days-since, and the nudged-this-period audit flag. There is
 * deliberately NO clinical field — the recall is keyed off a timestamp ({@code lastVisitAt} / a
 * COMPLETED appointment's start), never a reason for return (the {@code Appointment} fence F1, mirrored
 * here).
 *
 * <p>Like {@code WaitlistBoardEntryDTO}/{@code ConciergeConversationSummaryDTO}, a flat projection: the
 * {@code contactId} is surfaced as an id the board resolves; {@code name} is the one cross-collection
 * enrichment (the resolved {@code Contact}'s display name, best-effort — null when the contact is no
 * longer materialized).
 *
 * @param contactId          the lapsed patient (resolve to the chart-side record board-side)
 * @param name               the contact's display name, or null when the contact is not resolvable
 * @param lastVisitAt        the most-recent prior-visit timestamp ({@code lastVisitAt} or a COMPLETED
 *                           appointment's start) — a metadata timestamp, never a clinical reason
 * @param daysSinceLastVisit whole days between {@code lastVisitAt} and now (the recall-overdue sort key)
 * @param nudgedThisPeriod   whether FD-2's nightly recall sweep already sent a recare nudge this
 *                           period (ISO week) for this contact — so the board can avoid a manual
 *                           double-nudge
 */
public record RecallDueDTO(
        UUID contactId,
        String name,
        Instant lastVisitAt,
        long daysSinceLastVisit,
        boolean nudgedThisPeriod) {
}
