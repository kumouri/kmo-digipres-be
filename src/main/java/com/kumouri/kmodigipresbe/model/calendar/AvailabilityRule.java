package com.kumouri.kmodigipresbe.model.calendar;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A weekly recurring availability window — e.g. "Monday 09:00 to 17:00, in
 * America/Chicago." {@link BookingLink#getTimezone()} owns the timezone; this
 * rule is interpreted relative to it.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityRule {
    private DayOfWeek dayOfWeek;
    private LocalTime startTime;
    private LocalTime endTime;
}
