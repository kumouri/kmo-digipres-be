package com.kumouri.kmodigipresbe.module.waitlist;

import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistRankingService;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E4 — pure (no-Docker) unit test for the inverted-show-risk ranking + filtering + FIFO tiebreak. Proves
 * the deterministic polarity (reliable regular &lt; cold-start &lt; lapsed/some-no-show &lt; flaky) and the
 * slot filters, with no Spring context / Mongo (the {@code WaitlistRankingService} is pure).
 */
class WaitlistRankingServiceTest {

    private final WaitlistRankingService ranking = new WaitlistRankingService();

    private static final String SLOT_TYPE = "salon-booking";

    private WaitlistSlot slot(UUID providerId, Instant start) {
        return WaitlistSlot.builder()
                .slotType(SLOT_TYPE).slotKey("slot-1").providerId(providerId)
                .slotStart(start).slotEnd(start.plus(Duration.ofHours(1))).durationMinutes(60)
                .build();
    }

    private WaitlistEntry entry(String type, UUID providerId, int noShows, int visits,
                                Instant lastVisit, Instant createdAt) {
        return WaitlistEntry.builder()
                .id(UUID.randomUUID()).tenantId(UUID.randomUUID()).contactId(UUID.randomUUID())
                .slotType(type).providerId(providerId).smsOptIn(true)
                .status(WaitlistEntry.Status.OPEN)
                .priorNoShowCount(noShows).priorVisitCount(visits).lastVisitAt(lastVisit)
                .createdAt(createdAt)
                .build();
    }

    @Test
    void ranksReliableAboveColdStartAboveLapsedAboveFlaky() {
        Instant now = Instant.now();
        // Reliable regular: recent visits, no no-shows -> 0.2 (low risk, ranks FIRST).
        WaitlistEntry reliable = entry(null, null, 0, 5, now.minus(Duration.ofDays(10)),
                now.minus(Duration.ofDays(5)));
        // Cold start: no history -> 0.2 (never punished), but a later join time than reliable.
        WaitlistEntry cold = entry(null, null, 0, 0, null, now.minus(Duration.ofDays(4)));
        // Lapsed: a visit but > 90d ago, no no-shows -> 0.5.
        WaitlistEntry lapsed = entry(null, null, 0, 3, now.minus(Duration.ofDays(200)),
                now.minus(Duration.ofDays(3)));
        // Flaky: a real no-show rate (>= 0.34) -> 0.8 (ranks LAST).
        WaitlistEntry flaky = entry(null, null, 2, 1, now.minus(Duration.ofDays(12)),
                now.minus(Duration.ofDays(2)));

        List<WaitlistRankingService.RankedEntry> ranked =
                ranking.rank(List.of(flaky, lapsed, cold, reliable), slot(null, now.plus(Duration.ofHours(4))))
                        .block();

        assertThat(ranked).hasSize(4);
        // reliable (0.2, earliest) , cold (0.2, later join) , lapsed (0.5) , flaky (0.8)
        assertThat(ranked.get(0).entry().getId()).isEqualTo(reliable.getId());
        assertThat(ranked.get(1).entry().getId()).isEqualTo(cold.getId());
        assertThat(ranked.get(2).entry().getId()).isEqualTo(lapsed.getId());
        assertThat(ranked.get(3).entry().getId()).isEqualTo(flaky.getId());
        assertThat(ranked.get(0).showRisk()).isEqualTo(0.2);
        assertThat(ranked.get(2).showRisk()).isEqualTo(0.5);
        assertThat(ranked.get(3).showRisk()).isEqualTo(0.8);
    }

    @Test
    void fifoTiebreakAmongEqualRisk() {
        Instant now = Instant.now();
        WaitlistEntry later = entry(null, null, 0, 0, null, now.minus(Duration.ofMinutes(5)));
        WaitlistEntry earlier = entry(null, null, 0, 0, null, now.minus(Duration.ofMinutes(60)));

        List<WaitlistRankingService.RankedEntry> ranked =
                ranking.rank(List.of(later, earlier), slot(null, now.plus(Duration.ofHours(4)))).block();

        // Both cold-start (0.2) -> earlier joiner ranks first (FIFO).
        assertThat(ranked.get(0).entry().getId()).isEqualTo(earlier.getId());
        assertThat(ranked.get(1).entry().getId()).isEqualTo(later.getId());
    }

    @Test
    void filtersOutNonOptIn_wrongSlotType_wrongProvider_outOfWindow() {
        Instant now = Instant.now();
        Instant slotStart = now.plus(Duration.ofHours(4));
        UUID provider = UUID.randomUUID();

        WaitlistEntry optedOut = entry(null, null, 0, 1, now.minus(Duration.ofDays(1)), now)
                .toBuilder().smsOptIn(false).build();
        WaitlistEntry wrongType = entry("health-appt", null, 0, 1, now.minus(Duration.ofDays(1)), now);
        WaitlistEntry wrongProvider = entry(null, UUID.randomUUID(), 0, 1, now.minus(Duration.ofDays(1)), now);
        WaitlistEntry tooLate = entry(null, null, 0, 1, now.minus(Duration.ofDays(1)), now)
                .toBuilder().latestStart(now.plus(Duration.ofHours(1))).build(); // slot is +4h
        WaitlistEntry matches = entry(SLOT_TYPE, provider, 0, 1, now.minus(Duration.ofDays(1)), now);

        List<WaitlistRankingService.RankedEntry> ranked =
                ranking.rank(List.of(optedOut, wrongType, wrongProvider, tooLate, matches),
                        slot(provider, slotStart)).block();

        assertThat(ranked).hasSize(1);
        assertThat(ranked.get(0).entry().getId()).isEqualTo(matches.getId());
    }

    @Test
    void emptyPool_emptyResult() {
        assertThat(ranking.rank(List.of(), slot(null, Instant.now())).block()).isEmpty();
    }
}
