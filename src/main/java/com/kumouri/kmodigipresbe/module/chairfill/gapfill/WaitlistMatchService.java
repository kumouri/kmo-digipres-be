package com.kumouri.kmodigipresbe.module.chairfill.gapfill;

import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * ChairFill CF-3 — ranks the {@link WaitlistEntry} pool for a freed slot, <strong>most-likely-to-show
 * first</strong>. This is the CF-1 no-show model <em>inverted</em>: the gap-fill wants the client least
 * likely to no-show at the top of the offer list, so a low no-show risk sorts first.
 *
 * <h2>Why a mirror, not a call into {@code NoShowRiskScoringService}</h2>
 * The CF-1 scorer's {@code features(...)} / {@code scoreWithRules(...)} are private and tightly coupled
 * to scoring an <em>upcoming Booking</em> (it stamps {@code Booking.noShowRisk}); here we need a
 * per-<em>contact</em> show-likelihood at gap-fill time, with no Booking to score. So this mirrors the
 * exact CF-1 feature priors ({@code priorNoShowRate}, {@code priorBookingCount},
 * {@code daysSinceLastVisit}) and the exact deterministic CF-1 rules-tier scoring (same thresholds,
 * same polarity NO_SHOW=1), then ranks ascending by that no-show score. This keeps the ranking
 * deterministic + test-stable (the {@code scoresStableAcrossRuns} discipline) and the shipped CF-1
 * scorer untouched. The ML model is intentionally NOT used here — gap-fill ranking only needs the
 * relative ordering the rules give (lapsed/no-history clients sink, reliable regulars rise), and a
 * deterministic ranking is far easier to demo + defend than a per-run-varying model order.
 *
 * <p><strong>Filtering:</strong> an entry only matches a freed slot when its optional filters allow it —
 * {@code serviceMenuItemId} (if set) equals the freed service, {@code preferredStaffMemberId} (if set)
 * equals the freed stylist, and the freed {@code slotStart} falls within {@code [earliestStart,
 * latestStart]} (each bound optional). Only {@code OPEN} + {@code smsOptIn} entries are candidates
 * (consent default-safe, plan §4); an entry whose contact carries the {@code sms-opt-out} tag is dropped
 * later by the {@code GapFillService} send gate.
 */
@Slf4j
@RequiredArgsConstructor
public class WaitlistMatchService {

    private final BookingRepository bookings;

    /** A ranked waitlist candidate: the entry + its computed no-show risk (lower = better, ranks first). */
    public record RankedEntry(WaitlistEntry entry, double noShowRisk) {
    }

    /**
     * Returns the OPEN, opted-in, slot-matching entries for {@code tenantId}, ranked most-likely-to-show
     * first (ascending no-show risk; ties broken by the entry's creation order — earlier joiners first,
     * a fair FIFO tiebreak). Loads the tenant's full booking history once to compute per-contact priors.
     */
    public Mono<List<RankedEntry>> rank(UUID tenantId, List<WaitlistEntry> openEntries,
                                        String freedServiceMenuItemId, UUID freedStaffMemberId,
                                        Instant slotStart) {
        List<WaitlistEntry> candidates = openEntries.stream()
                .filter(WaitlistEntry::isSmsOptIn)
                .filter(e -> e.getStatus() == WaitlistEntry.Status.OPEN)
                .filter(e -> matchesSlot(e, freedServiceMenuItemId, freedStaffMemberId, slotStart))
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return Mono.just(List.of());
        }
        return bookings.findAllByTenantId(tenantId).collectList()
                .map(all -> rankCandidates(candidates, all));
    }

    private boolean matchesSlot(WaitlistEntry e, String freedServiceMenuItemId,
                                UUID freedStaffMemberId, Instant slotStart) {
        if (e.getServiceMenuItemId() != null
                && !e.getServiceMenuItemId().equals(freedServiceMenuItemId)) {
            return false;
        }
        if (e.getPreferredStaffMemberId() != null
                && !e.getPreferredStaffMemberId().equals(freedStaffMemberId)) {
            return false;
        }
        if (slotStart != null) {
            if (e.getEarliestStart() != null && slotStart.isBefore(e.getEarliestStart())) {
                return false;
            }
            if (e.getLatestStart() != null && slotStart.isAfter(e.getLatestStart())) {
                return false;
            }
        }
        return true;
    }

    private List<RankedEntry> rankCandidates(List<WaitlistEntry> candidates, List<Booking> all) {
        Instant now = Instant.now();
        // Terminal bookings per contact (the CF-1 training/history universe; CANCELLED excluded —
        // a cancel is not a no-show, mirroring NoShowRiskScoringService.scoreUpcoming).
        Map<UUID, List<Booking>> terminalByContact = all.stream()
                .filter(b -> b.getStatus() == BookingStatus.COMPLETED
                        || b.getStatus() == BookingStatus.NO_SHOW)
                .filter(b -> b.getContactId() != null && b.getScheduledStart() != null)
                .collect(Collectors.groupingBy(Booking::getContactId));

        List<RankedEntry> ranked = new ArrayList<>(candidates.size());
        for (WaitlistEntry e : candidates) {
            List<Booking> history = e.getContactId() != null
                    ? terminalByContact.getOrDefault(e.getContactId(), List.of())
                    : List.of();
            ranked.add(new RankedEntry(e, noShowRisk(history, now)));
        }
        // Ascending no-show risk = most-likely-to-show first; FIFO tiebreak on join time.
        ranked.sort(Comparator
                .comparingDouble(RankedEntry::noShowRisk)
                .thenComparing(r -> createdAtOrMax(r.entry())));
        return ranked;
    }

    private static Instant createdAtOrMax(WaitlistEntry e) {
        return e.getCreatedAt() != null ? e.getCreatedAt() : Instant.MAX;
    }

    /**
     * The CF-1 deterministic no-show risk for a contact, scored from their terminal-booking history as
     * of {@code now}. Mirrors {@code NoShowRiskScoringService.scoreWithRules} on the three history-derived
     * features (the only ones knowable without a specific Booking): {@code priorNoShowRate},
     * {@code priorBookingCount}, {@code daysSinceLastVisit}. The lead-time / deposit / price features are
     * Booking-specific (no slot to evaluate at ranking time) so the no-deposit branch is treated as the
     * default; this is the same polarity (higher = more likely to no-show) so a low value ranks first.
     */
    private double noShowRisk(List<Booking> history, Instant now) {
        long priorNoShows = history.stream().filter(b -> b.getStatus() == BookingStatus.NO_SHOW).count();
        long priorTerminal = history.size();
        double priorNoShowRate = priorTerminal > 0 ? (double) priorNoShows / priorTerminal : 0.0;
        double daysSinceLastVisit = daysSinceLastVisit(history, now);

        // CF-1 rules polarity (scoreWithRules), history-only subset:
        // HIGH (0.8) if a real no-show rate; INSUFFICIENT_DATA/LOW (0.2) for no history; MEDIUM (0.5)
        // for a lapsed-with-history client; LOW (0.2) for a strong recent regular.
        if (priorNoShowRate >= 0.34) {
            return 0.8;
        }
        boolean noHistory = priorTerminal == 0;
        if (noHistory) {
            return 0.2; // a first-timer is treated as low-risk (never punished — CF-1 D1 cold-start)
        }
        boolean lapsed = daysSinceLastVisit > 90.0;
        if (priorNoShowRate > 0.0 || lapsed) {
            return 0.5;
        }
        return 0.2;
    }

    /** Days since the contact's most-recent terminal visit, capped 365; 365 sentinel when none. */
    private double daysSinceLastVisit(List<Booking> history, Instant now) {
        Instant lastVisit = null;
        for (Booking b : history) {
            if (b.getScheduledStart() == null) continue;
            if (b.getScheduledStart().isBefore(now)
                    && (lastVisit == null || b.getScheduledStart().isAfter(lastVisit))) {
                lastVisit = b.getScheduledStart();
            }
        }
        if (lastVisit == null) return 365.0;
        long days = Math.max(0L, ChronoUnit.DAYS.between(lastVisit, now));
        return Math.min(365.0, (double) days);
    }
}
