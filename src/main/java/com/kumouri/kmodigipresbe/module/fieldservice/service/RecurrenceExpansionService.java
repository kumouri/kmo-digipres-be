package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import net.fortuna.ical4j.model.Recur;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Expands an RFC 5545 RRULE string into concrete occurrence instants inside a window.
 *
 * <p>Phase 3 keeps this in-memory and stateless. Backfilling materialized child
 * work orders for an entire year of weekly visits is fine here (≤ ~60 expansions
 * per call); larger windows would want pagination or chunking, but the
 * field-service use cases (NMM monthly visits, weekly safety checks) fit inside
 * this contract comfortably.
 */
public class RecurrenceExpansionService {

    /**
     * @param rrule  RFC 5545 RRULE string, e.g. {@code "FREQ=MONTHLY;BYMONTHDAY=15"}.
     *               The {@code RRULE:} prefix is optional and stripped.
     * @param seed   the first occurrence (DTSTART semantics) — used as the anchor
     *               for BYMONTHDAY / BYDAY / etc.
     * @param from   inclusive window start.
     * @param to     exclusive window end.
     * @return       the occurrences whose start time falls in {@code [from, to)},
     *               in chronological order.
     */
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
