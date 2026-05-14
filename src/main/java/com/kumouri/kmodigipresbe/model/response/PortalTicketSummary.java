package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.activity.Activity;

import java.time.Instant;

/**
 * Portal-facing ticket projection. Tickets are persisted as Activity rows with
 * {@code type=TICKET}; this projection drops the channel-internal fields (payload,
 * ownerId, subjectType/subjectId, direction, tenantId) so the portal caller only
 * sees what they submitted.
 */
public record PortalTicketSummary(
        String id,
        String summary,
        String body,
        Instant occurredAt) {

    public static PortalTicketSummary from(Activity a) {
        return new PortalTicketSummary(
                a.getId() == null ? null : a.getId().toString(),
                a.getSummary(),
                a.getBody(),
                a.getOccurredAt());
    }
}
