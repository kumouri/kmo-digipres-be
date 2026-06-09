package com.kumouri.kmodigipresbe.module.homeservices.callback;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 — {@link RequestedWindowParser} pure best-effort parse unit test (no Docker). Proves the common
 * recognized shapes + that an unparseable phrase returns {@code null} (never throws).
 */
class RequestedWindowParserTest {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void nowAndAsap_returnNow() {
        assertThat(RequestedWindowParser.parse("now", CLOCK)).isEqualTo(NOW);
        assertThat(RequestedWindowParser.parse("call me ASAP please", CLOCK)).isEqualTo(NOW);
        assertThat(RequestedWindowParser.parse("right away", CLOCK)).isEqualTo(NOW);
    }

    @Test
    void inMinutes_addsMinutes() {
        assertThat(RequestedWindowParser.parse("in 30 min", CLOCK))
                .isEqualTo(NOW.plus(Duration.ofMinutes(30)));
        assertThat(RequestedWindowParser.parse("call me in 45 minutes", CLOCK))
                .isEqualTo(NOW.plus(Duration.ofMinutes(45)));
    }

    @Test
    void inHours_addsHours() {
        assertThat(RequestedWindowParser.parse("in 2 hours", CLOCK))
                .isEqualTo(NOW.plus(Duration.ofHours(2)));
    }

    @Test
    void clockTime_resolvesToTodayOrTomorrow() {
        // 5pm UTC is after 12:00 UTC → today.
        Instant fivePm = RequestedWindowParser.parse("after 5pm", CLOCK);
        assertThat(fivePm).isEqualTo(Instant.parse("2026-06-09T17:00:00Z"));

        // 9am UTC has already passed today (now is noon) → tomorrow.
        Instant nineAm = RequestedWindowParser.parse("at 9am", CLOCK);
        assertThat(nineAm).isEqualTo(Instant.parse("2026-06-10T09:00:00Z"));
    }

    @Test
    void unparseableOrBlank_returnsNullNeverThrows() {
        assertThat(RequestedWindowParser.parse(null, CLOCK)).isNull();
        assertThat(RequestedWindowParser.parse("   ", CLOCK)).isNull();
        assertThat(RequestedWindowParser.parse("tomorrow morning", CLOCK)).isNull();
        assertThat(RequestedWindowParser.parse("whenever you can", CLOCK)).isNull();
    }
}
