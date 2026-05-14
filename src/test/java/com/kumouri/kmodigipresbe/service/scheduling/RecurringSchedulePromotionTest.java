package com.kumouri.kmodigipresbe.service.scheduling;

import com.kumouri.kmodigipresbe.module.fieldservice.service.RecurrenceExpansionService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts that the Phase 3 {@link RecurrenceExpansionService}, refactored in 9b to
 * delegate to {@link Rfc5545RecurringSchedule}, produces byte-for-byte the same
 * expansions across the RRULE shapes the existing
 * {@code RecurrenceExpansionServiceTest} exercises. Belt + braces for the
 * promotion.
 */
class RecurringSchedulePromotionTest {

    private final Rfc5545RecurringSchedule schedule = new Rfc5545RecurringSchedule();
    private final RecurrenceExpansionService facade = new RecurrenceExpansionService(schedule);

    @Test
    void weeklyByDay_facadeMatchesSchedule() {
        Instant seed = Instant.parse("2026-01-05T09:00:00Z");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-04-01T00:00:00Z");

        List<Instant> viaFacade = facade.expand("FREQ=WEEKLY;BYDAY=MO", seed, from, to);
        List<Instant> viaSchedule = schedule.expand("FREQ=WEEKLY;BYDAY=MO", seed, from, to);

        assertThat(viaFacade).isEqualTo(viaSchedule);
        assertThat(viaFacade).hasSize(13);
    }

    @Test
    void monthlyByMonthDay_facadeMatchesSchedule() {
        Instant seed = Instant.parse("2026-01-15T14:30:00Z");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-07-01T00:00:00Z");

        assertThat(facade.expand("FREQ=MONTHLY;BYMONTHDAY=15", seed, from, to))
                .isEqualTo(schedule.expand("FREQ=MONTHLY;BYMONTHDAY=15", seed, from, to));
    }

    @Test
    void dailyWithCount_facadeMatchesSchedule() {
        Instant seed = Instant.parse("2026-01-05T09:00:00Z");
        Instant from = Instant.parse("2026-01-01T00:00:00Z");
        Instant to = Instant.parse("2026-02-01T00:00:00Z");

        assertThat(facade.expand("RRULE:FREQ=DAILY;COUNT=3", seed, from, to))
                .isEqualTo(schedule.expand("RRULE:FREQ=DAILY;COUNT=3", seed, from, to))
                .hasSize(3);
    }

    @Test
    void nextDefault_returnsFirstFutureOccurrence() {
        Instant seed = Instant.parse("2026-01-05T09:00:00Z"); // Mon 09:00
        // {@code after} sits between Jan 12 09:00 (a Mon, after seed) and Jan 19 09:00.
        Instant after = Instant.parse("2026-01-12T10:00:00Z");

        Optional<Instant> next = schedule.next("FREQ=WEEKLY;BYDAY=MO", seed, after);

        assertThat(next).contains(Instant.parse("2026-01-19T09:00:00Z"));
    }

    @Test
    void nextDefault_emptyWhenNoFutureOccurrence() {
        Instant seed = Instant.parse("2026-01-05T09:00:00Z");
        Instant after = Instant.parse("2026-01-20T00:00:00Z");

        Optional<Instant> next = schedule.next("FREQ=DAILY;COUNT=3", seed, after);

        assertThat(next).isEmpty();
    }
}
