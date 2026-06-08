package com.kumouri.kmodigipresbe.module.realestate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Real Estate Concierge (RE-3) — one candidate showing slot the concierge offered the buyer over SMS,
 * embedded on the {@link ConciergeConversation} while it is in {@link ConversationState#OFFERING_SLOTS}
 * (RE-3 §5 / decision 4).
 *
 * <p>The slots are persisted (rather than re-derived) so the buyer's reply — a number/slot token like
 * {@code "1"} or {@code "2"} — resolves to the exact {@code start}/{@code end} the concierge offered,
 * without re-running availability between turns (and so a stale offer can't drift). The {@link #ordinal}
 * is the 1-based number shown to the buyer ("reply 1 or 2"); the {@link #label} is the human phrase the
 * offer SMS used ("Sat 2:00 PM"). When the buyer picks one, {@link com.kumouri.kmodigipresbe.module
 * .realestate.concierge.ShowingBookingService} writes a {@code Meeting} for the chosen {@code start}/{@code
 * end} (the {@code CalComWebhookService.reconcileUpsert} projection shape — the demo writes it directly;
 * production flips to a live Cal.com booking + the shipped webhook reconcile).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OfferedShowingSlot {

    /** The 1-based number presented to the buyer ("reply 1 or 2"). The pick key. */
    private int ordinal;

    /** The human phrase the offer SMS used for this slot, e.g. "Sat 2:00 PM". */
    private String label;

    /** The slot start (local to the listing). */
    private LocalDateTime start;

    /** The slot end. */
    private LocalDateTime end;
}
