package com.kumouri.kmodigipresbe.module.fieldservice;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.fieldservice.service.RecurrenceExpansionService;
import com.kumouri.kmodigipresbe.service.scheduling.Rfc5545RecurringSchedule;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceExpansionServiceTest {

    // Phase 9b promoted the RRULE engine into core; the facade just delegates.
    private final RecurrenceExpansionService svc =
            new RecurrenceExpansionService(new Rfc5545RecurringSchedule());

    @Test
    void weeklyRecurrence_expandsCorrectlyAcrossQuarter() {
        Instant seed = Instant.parse("2026-01-05T09:00:00Z"); // a Monday
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z"); // 3 months
        List<Instant> out = svc.expand("FREQ=WEEKLY;BYDAY=MO", seed, from, to);

        // Mondays from Jan 5 through Mar 30 inclusive = 13 occurrences
        assertThat(out).hasSize(13);
        assertThat(out.get(0)).isEqualTo(seed);
        assertThat(out.get(1)).isEqualTo(seed.plus(7, ChronoUnit.DAYS));
        assertThat(out.get(out.size() - 1)).isEqualTo(Instant.parse("2026-03-30T09:00:00Z"));
    }

    @Test
    void monthlyByMonthDay_expandsCorrectly() {
        Instant seed = Instant.parse("2026-01-15T14:30:00Z");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");
        List<Instant> out = svc.expand("FREQ=MONTHLY;BYMONTHDAY=15", seed, from, to);

        assertThat(out).hasSize(6);
        assertThat(out.get(0)).isEqualTo(seed);
        assertThat(out.get(5)).isEqualTo(Instant.parse("2026-06-15T14:30:00Z"));
    }

    @Test
    void rrulePrefix_stripped() {
        Instant seed = Instant.parse("2026-01-05T09:00:00Z");
        List<Instant> withPrefix = svc.expand("RRULE:FREQ=DAILY;COUNT=3", seed,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
        List<Instant> withoutPrefix = svc.expand("FREQ=DAILY;COUNT=3", seed,
                Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-02-01T00:00:00Z"));
        assertThat(withPrefix).isEqualTo(withoutPrefix);
    }

    @Test
    void nullOrBlankRrule_returnsEmpty() {
        Instant seed = Instant.now();
        Instant from = seed.minusSeconds(60);
        Instant to = seed.plusSeconds(60);
        assertThat(svc.expand(null, seed, from, to)).isEmpty();
        assertThat(svc.expand("  ", seed, from, to)).isEmpty();
    }

    @Test
    void invalidRrule_throwsTranslated() {
        Instant seed = Instant.now();
        assertThatThrownBy(() -> svc.expand("NOT_A_REAL_RRULE",
                seed, seed, seed.plusSeconds(86400)))
                .isInstanceOf(DigiPresBeException.class);
    }
}
