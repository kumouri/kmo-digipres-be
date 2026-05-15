package com.kumouri.kmodigipresbe.module.salonspa.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * An individual service offered by a salon/spa tenant — embedded inside
 * {@link ServiceMenu}. Not a top-level Mongo document.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ServiceMenuItem {

    /** Stable identifier within the parent menu — used in booking requests. */
    private String id;

    private String name;

    /** Duration in minutes. */
    private int durationMinutes;

    private BigDecimal price;

    /**
     * Staff members eligible to perform this service. Empty list = any staff
     * member can be booked. Non-empty restricts to the listed {@link StaffMember} ids.
     */
    @Builder.Default
    private List<UUID> eligibleStaffIds = List.of();

    private boolean depositRequired;

    /** Deposit amount charged at booking; null when {@code depositRequired=false}. */
    private BigDecimal depositAmount;

    /** Human-readable cancellation policy shown at booking time. */
    private String cancellationPolicy;
}
