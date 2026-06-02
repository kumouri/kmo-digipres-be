package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.user.User;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Read projection of a staff / contractor {@link User} for the team directory (Phase J).
 * Deliberately drops {@code passwordHash} and {@code contactId} — only what the admin
 * directory needs crosses the wire (the portal {@code *Summary} record precedent).
 */
public record TeamMemberView(
        UUID id,
        String email,
        String displayName,
        Set<String> roles,
        String status,
        String portal,
        BigDecimal defaultBillRate,
        BigDecimal defaultCostRate,
        Instant createdAt,
        Instant updatedAt) {

    public static TeamMemberView from(User u) {
        return new TeamMemberView(
                u.getId(),
                u.getEmail(),
                u.getDisplayName(),
                u.getRoles(),
                u.getStatus() != null ? u.getStatus().name() : null,
                u.getPortal() != null ? u.getPortal().name() : null,
                u.getDefaultBillRate(),
                u.getDefaultCostRate(),
                u.getCreatedAt(),
                u.getUpdatedAt());
    }
}
