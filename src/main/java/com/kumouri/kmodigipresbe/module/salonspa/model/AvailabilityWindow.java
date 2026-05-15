package com.kumouri.kmodigipresbe.module.salonspa.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.DayOfWeek;
import java.time.LocalTime;

/**
 * A recurring weekly availability block for a {@link StaffMember} — embedded,
 * not a top-level Mongo document.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AvailabilityWindow {

    private DayOfWeek dayOfWeek;

    /** Inclusive start of available window (local time, tenant timezone applied at display). */
    private LocalTime startTime;

    /** Exclusive end of available window. */
    private LocalTime endTime;
}
