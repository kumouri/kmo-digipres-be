package com.kumouri.kmodigipresbe.service.scheduling;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import net.fortuna.ical4j.model.Recur;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * ical4j-backed {@link RecurringSchedule}. Lifted from Phase 3's
 * {@code RecurrenceExpansionService} verbatim — same engine, same window semantics,
 * same error mapping — so existing field-service callers see no behavior change
 * (asserted by {@code RecurringSchedulePromotionTest}).
 *
 * <p>Stateless and in-memory. Backfilling a year of weekly visits (~52 expansions)
 * is fine here; larger windows want pagination, which Phase 10's
 * {@code ServiceAgreementSchedulerService} will introduce when it needs to.
 *
 * <p>Error code {@code 1300} for invalid RRULEs is the Phase 3 contract and stays.
 */
@Component
public class Rfc5545RecurringSchedule implements RecurringSchedule {

    @Override
    public List<Instant> expand(String rrule, Instant seed, Instant from, Instant to) {
        if (rrule == null || rrule.isBlank()) return List.of();
        String body = rrule.startsWith("RRULE:") ? rrule.substring("RRULE:".length()) : rrule;
        try {
            Recur<ZonedDateTime> recur = new Recur<>(body);
            ZonedDateTime seedZ = seed.atZone(ZoneOffset.UTC);
            ZonedDateTime fromZ = from.atZone(ZoneOffset.UTC);
            ZonedDateTime toZ = to.atZone(ZoneOffset.UTC);
            return recur.getDates(seedZ, fromZ, toZ).stream()
                    .map(ZonedDateTime::toInstant)
                    .toList();
        } catch (RuntimeException ex) {
            throw new DigiPresBeException(
                    "Invalid RRULE: " + ex.getMessage(), 1300, 400);
        }
    }
}
