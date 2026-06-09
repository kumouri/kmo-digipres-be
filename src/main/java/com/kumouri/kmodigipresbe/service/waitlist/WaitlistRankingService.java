package com.kumouri.kmodigipresbe.service.waitlist;

import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * E4 — ranks the {@link WaitlistEntry} pool for a freed slot, <strong>most-likely-to-show first</strong>.
 * The generic, vertical-agnostic sibling of ChairFill CF-3's {@code WaitlistMatchService} (which stays
 * byte-equivalent): same inverted-show-risk intent (the client least likely to no-show sorts to the top of
 * the offer list), same deterministic rules-tier polarity, same FIFO tiebreak — but with
 * <strong>no salon Booking coupling</strong>.
 *
 * <h2>Why a reimplementation, not a call into {@code WaitlistMatchService} (design directive #1)</h2>
 * CF-3's matcher derives show-likelihood by loading the salon's <em>Booking history</em>
 * ({@code bookings.findAllByTenantId}) and grouping terminal {@code Booking}s per contact — a salon-coupled
 * load the generic engine cannot make (it has no Booking, by design). The genuinely-shared part is only the
 * ~10-line rules polarity below. Per the brief ("default is reimplement-in-engine + note the minor
 * duplication"; "Do NOT refactor chairfill in this phase"), this reimplements those rules as a pure
 * {@code static double showRisk(...)} over the <em>entry-carried</em> stats
 * ({@code priorNoShowCount}/{@code priorVisitCount}/{@code lastVisitAt}) instead of a Booking history. The
 * data source differs, so it is not literal duplication; the ~10-line polarity overlap with
 * {@code WaitlistMatchService.noShowRisk} is accepted + documented. Keeping it pure + deterministic makes
 * the ranking test-stable and the shipped CF-1 scorer untouched.
 *
 * <p><strong>Filtering:</strong> an entry only matches a freed slot when its optional filters allow it —
 * {@code slotType} (if set) equals the freed slot type, {@code providerId} (if set) equals the freed
 * provider, and the freed {@code slotStart} falls within {@code [earliestStart, latestStart]} (each bound
 * optional). Only {@code OPEN} + {@code smsOptIn} entries are candidates (consent default-safe); an entry
 * whose contact carries the {@code sms-opt-out} tag is dropped later by the {@code GapFillEngine} send gate.
 */
@Slf4j
public class WaitlistRankingService {

    /** A ranked waitlist candidate: the entry + its computed show-risk (lower = better, ranks first). */
    public record RankedEntry(WaitlistEntry entry, double showRisk) {
    }

    /**
     * Returns the OPEN, opted-in, slot-matching entries for the slot, ranked most-likely-to-show first
     * (ascending show-risk; ties broken by the entry's creation order — earlier joiners first, a fair FIFO
     * tiebreak). Pure + deterministic — the show-risk is derived from each entry's own carried stats, so
     * there is no vertical-coupled history load. Returns a {@code Mono} for signature symmetry with CF-3's
     * {@code WaitlistMatchService.rank} (the body is synchronous).
     */
    public Mono<List<RankedEntry>> rank(List<WaitlistEntry> openEntries, WaitlistSlot slot) {
        Instant now = Instant.now();
        List<RankedEntry> ranked = openEntries.stream()
                .filter(WaitlistEntry::isSmsOptIn)
                .filter(e -> e.getStatus() == WaitlistEntry.Status.OPEN)
                .filter(e -> matchesSlot(e, slot))
                .map(e -> new RankedEntry(e, showRisk(e, now)))
                .sorted(Comparator
                        .comparingDouble(RankedEntry::showRisk)
                        .thenComparing(r -> createdAtOrMax(r.entry())))
                .collect(Collectors.toCollection(ArrayList::new));
        return Mono.just(ranked);
    }

    private static boolean matchesSlot(WaitlistEntry e, WaitlistSlot slot) {
        if (e.getSlotType() != null && !e.getSlotType().equals(slot.slotType())) {
            return false;
        }
        if (e.getProviderId() != null && !e.getProviderId().equals(slot.providerId())) {
            return false;
        }
        Instant slotStart = slot.slotStart();
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

    private static Instant createdAtOrMax(WaitlistEntry e) {
        return e.getCreatedAt() != null ? e.getCreatedAt() : Instant.MAX;
    }

    /**
     * The deterministic show-risk for an entry (higher = more likely to no-show, so a low value ranks
     * first). Mirrors the CF-1 {@code scoreWithRules} polarity on the three history-derived features —
     * here read from the entry's carried stats rather than a Booking history:
     * {@code priorNoShowRate} = {@code priorNoShowCount / (priorNoShowCount + priorVisitCount)},
     * {@code priorTerminal} = the sum, {@code daysSinceLastVisit} from {@code lastVisitAt}.
     *
     * <ul>
     *   <li>HIGH (0.8) if a real no-show rate (≥ 0.34);</li>
     *   <li>LOW (0.2) for no history (a cold-start client is never punished — the CF-1 D1 posture);</li>
     *   <li>MEDIUM (0.5) for a lapsed-with-history (> 90d) or some-prior-no-show client;</li>
     *   <li>LOW (0.2) for a strong recent regular.</li>
     * </ul>
     */
    static double showRisk(WaitlistEntry e, Instant now) {
        long priorNoShows = Math.max(0, e.getPriorNoShowCount());
        long priorVisits = Math.max(0, e.getPriorVisitCount());
        long priorTerminal = priorNoShows + priorVisits;
        double priorNoShowRate = priorTerminal > 0 ? (double) priorNoShows / priorTerminal : 0.0;
        double daysSinceLastVisit = daysSinceLastVisit(e.getLastVisitAt(), now);

        if (priorNoShowRate >= 0.34) {
            return 0.8;
        }
        boolean noHistory = priorTerminal == 0;
        if (noHistory) {
            return 0.2;
        }
        boolean lapsed = daysSinceLastVisit > 90.0;
        if (priorNoShowRate > 0.0 || lapsed) {
            return 0.5;
        }
        return 0.2;
    }

    /** Days since the entry's last visit, capped 365; 365 sentinel when null/future. */
    private static double daysSinceLastVisit(Instant lastVisitAt, Instant now) {
        if (lastVisitAt == null || !lastVisitAt.isBefore(now)) {
            return 365.0;
        }
        long days = Math.max(0L, ChronoUnit.DAYS.between(lastVisitAt, now));
        return Math.min(365.0, (double) days);
    }
}
