package com.kumouri.kmodigipresbe.module.homeservices.callback;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 — {@link CallbackRevenueRanker} pure-ordering unit test (no Docker). Proves the deterministic
 * lexicographic priority: job-value band &gt; urgency &gt; recency, and that null/garbage labels score
 * lowest (never throw).
 */
class CallbackRevenueRankerTest {

    private static final Instant T = Instant.parse("2026-06-09T12:00:00Z");

    @Test
    void bandDominatesUrgency() {
        // A SMALL+EMERGENCY job ranks BELOW a LARGE+ROUTINE job (band is the primary signal).
        long smallEmergency = CallbackRevenueRanker.score("SMALL", "EMERGENCY", T);
        long largeRoutine = CallbackRevenueRanker.score("LARGE", "ROUTINE", T);
        assertThat(largeRoutine).isGreaterThan(smallEmergency);
    }

    @Test
    void urgencyDominatesRecency_withinSameBand() {
        // Same band: an EMERGENCY older request still ranks above a ROUTINE newer request.
        long emergencyOld = CallbackRevenueRanker.score("MEDIUM", "EMERGENCY", T.minusSeconds(3600));
        long routineNew = CallbackRevenueRanker.score("MEDIUM", "ROUTINE", T);
        assertThat(emergencyOld).isGreaterThan(routineNew);
    }

    @Test
    void recencyBreaksTies_withinSameBandAndUrgency() {
        long newer = CallbackRevenueRanker.score("MEDIUM", "URGENT", T);
        long older = CallbackRevenueRanker.score("MEDIUM", "URGENT", T.minusSeconds(120));
        assertThat(newer).isGreaterThan(older);
    }

    @Test
    void nullAndGarbageLabelsScoreLowest_neverThrow() {
        long unknown = CallbackRevenueRanker.score(null, null, T);
        long garbage = CallbackRevenueRanker.score("HUGE", "WHENEVER", T);
        long small = CallbackRevenueRanker.score("SMALL", "ROUTINE", T);
        assertThat(unknown).isLessThan(small);
        assertThat(garbage).isLessThan(small);
        // Same lowest tier → recency-only difference, never an exception.
        assertThat(CallbackRevenueRanker.score(null, null, null)).isGreaterThanOrEqualTo(0L);
    }

    @Test
    void fullOrdering_highValueFirst() {
        record Card(String band, String urgency, String label) {
        }
        List<Card> cards = List.of(
                new Card("LARGE", "EMERGENCY", "hvac-emergency"),
                new Card("MEDIUM", "ROUTINE", "mid-routine"),
                new Card("SMALL", null, "small-untriaged"),
                new Card(null, null, "unknown"));

        List<String> ranked = Stream.of(cards.toArray(new Card[0]))
                .sorted(Comparator.comparingLong(
                        (Card c) -> CallbackRevenueRanker.score(c.band(), c.urgency(), T)).reversed())
                .map(Card::label)
                .toList();

        assertThat(ranked).containsExactly(
                "hvac-emergency", "mid-routine", "small-untriaged", "unknown");
    }
}
