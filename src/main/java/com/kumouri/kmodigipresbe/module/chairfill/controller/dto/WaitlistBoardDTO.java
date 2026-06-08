package com.kumouri.kmodigipresbe.module.chairfill.controller.dto;

import java.util.List;

/**
 * The ChairFill CF-5 waitlist board read response (CF-5a) — the salon's current gap-fill state in one
 * shot: the OPEN {@link com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry waitlist} (who
 * is waiting) plus the recent {@link com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer
 * offers} (who has been offered what, with status). Both lists are newest-first.
 *
 * <p>{@code GET /chairfill/waitlist/board} returns this single envelope so the board FE can hand-write
 * one typed client call and render both columns; the split {@code /entries} and {@code /offers}
 * endpoints stream the two lists individually for incremental refresh.
 *
 * @param openEntries  OPEN waitlist rows, newest join first
 * @param recentOffers recent offers (all statuses), newest sent first (capped by {@code limit})
 */
public record WaitlistBoardDTO(
        List<WaitlistBoardEntryDTO> openEntries,
        List<WaitlistOfferDTO> recentOffers) {
}
