package com.kumouri.kmodigipresbe.automation;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One-way notification that something happened. Published by {@code *Service} after
 * a successful save and consumed by {@link RuleEngine} + the webhook fan-out.
 *
 * <p>{@code payload} carries entity-specific data — for {@code DEAL_STAGE_CHANGED}:
 * {@code {fromStage, toStage, dealId, dealTitle, value}}; for {@code CONTACT_CREATED}:
 * a flat projection of the contact. Workflow rule conditions match on these keys.
 */
public record DomainEvent(
        String type,
        UUID tenantId,
        UUID subjectId,
        Map<String, Object> payload,
        Instant occurredAt) {

    public static DomainEvent of(String type, UUID tenantId, UUID subjectId,
                                 Map<String, Object> payload) {
        return new DomainEvent(type, tenantId, subjectId, payload, Instant.now());
    }
}
