package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.service.scheduling.RecurringSchedule;

import java.time.Instant;
import java.util.List;

/**
 * Field-service module's recurrence service. Phase 9 (sub-PR 9b) promoted the
 * underlying RRULE engine into core as {@link RecurringSchedule}; this class is
 * now a thin facade so existing callers ({@code WorkOrderService} and its tests)
 * keep working without modification.
 *
 * <p>Behavior is unchanged — same windows, same error code (1300 for invalid
 * RRULE), same handling of the optional {@code RRULE:} prefix. The
 * {@code RecurringSchedulePromotionTest} asserts identical expansions.
 */
public class RecurrenceExpansionService {

    private final RecurringSchedule delegate;

    public RecurrenceExpansionService(RecurringSchedule delegate) {
        this.delegate = delegate;
    }

    /**
     * @see RecurringSchedule#expand(String, Instant, Instant, Instant)
     */
    public List<Instant> expand(String rrule, Instant seed, Instant from, Instant to) {
        return delegate.expand(rrule, seed, from, to);
    }
}
