package com.kumouri.kmodigipresbe.model.request;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Create / update payload for a staff or contractor team member (Phase J).
 *
 * <p>All fields are optional on update (null = leave unchanged). On create, {@code email}
 * and {@code displayName} are required. {@code password} is optional — when supplied the
 * user is ACTIVE, otherwise INVITED (so the owner can pre-create a teammate who sets their
 * own password later). {@code roles} defaults to {@code STAFF}; {@code CONTRACTOR} implies
 * {@code STAFF} (rides the staff security chain).
 */
public record TeamMemberRequest(
        String email,
        String displayName,
        Set<String> roles,
        String status,
        String password,
        BigDecimal defaultBillRate,
        BigDecimal defaultCostRate) {
}
