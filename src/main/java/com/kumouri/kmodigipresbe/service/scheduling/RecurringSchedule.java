package com.kumouri.kmodigipresbe.service.scheduling;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Promoted out of {@code module/fieldservice/service/RecurrenceExpansionService} so
 * tenants of Phase 10 home-services ({@code ServiceAgreement → MaintenanceVisit})
 * and Phase 12 salon-spa ({@code MembershipPlan → ClassSession}) can reuse one
 * RRULE engine. The field-service module's existing service stays — it just
 * delegates here.
 *
 * <p>Implementations are RFC 5545-compliant. {@link Rfc5545RecurringSchedule} is the
 * canonical impl backed by ical4j; swapping providers later (or adding a
 * non-iCal variant for, e.g., natural-language schedules) is a one-class change.
 */
public interface RecurringSchedule {

    /**
     * Expand a recurrence rule to concrete occurrence instants in
     * {@code [from, to)}, anchored to {@code seed}.
     *
     * @param rrule RFC 5545 RRULE body (with or without the {@code RRULE:} prefix)
     * @param seed  the first occurrence (DTSTART semantics) — anchor for
     *              BYMONTHDAY / BYDAY / etc.
     * @param from  inclusive window start
     * @param to    exclusive window end
     */
    List<Instant> expand(String rrule, Instant seed, Instant from, Instant to);

    /**
     * Convenience: the first occurrence strictly after {@code after}. Implemented
     * on top of {@link #expand} so impls only need to nail down the expand semantics.
     *
     * <p>{@code seed} is required even for "next" queries because RRULEs anchor
     * derivations on it (e.g. {@code BYMONTHDAY=15} needs the seed's time-of-day;
     * {@code COUNT=N} is counted from the seed). This deviates from the plan §4.1
     * signature {@code next(rrule, after)} — see the PR description for the rationale.
     */
    default Optional<Instant> next(String rrule, Instant seed, Instant after) {
        Instant farFuture = Instant.ofEpochSecond(253_402_300_799L); // 9999-12-31T23:59:59Z
        return expand(rrule, seed, after, farFuture).stream().findFirst();
    }
}
