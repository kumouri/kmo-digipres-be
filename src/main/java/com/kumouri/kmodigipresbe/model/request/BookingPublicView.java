package com.kumouri.kmodigipresbe.model.request;

import java.time.Instant;
import java.util.List;

/**
 * Public-facing projection of a {@code BookingLink}. Carries the slot list for
 * the requested window plus just enough metadata to render a booking page —
 * never includes tenantId, ownerUserId, or any internal field.
 */
public record BookingPublicView(
        String slug,
        String title,
        String description,
        int durationMinutes,
        String timezone,
        List<Instant> availableSlots) {
}
