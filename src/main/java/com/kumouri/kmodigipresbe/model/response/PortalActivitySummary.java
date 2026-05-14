package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;

import java.time.Instant;

/**
 * Portal-facing activity projection. Omits {@code payload}, {@code ownerId},
 * {@code customFields}, {@code subjectType}/{@code subjectId} (implied — the timeline
 * is always for the caller's Contact), {@code completedAt}, {@code dueAt},
 * {@code tenantId}, and audit fields. Body is included so the FE can render the
 * timeline detail without a follow-up request.
 */
public record PortalActivitySummary(
        String id,
        ActivityType type,
        ActivityDirection direction,
        String summary,
        String body,
        Instant occurredAt) {

    public static PortalActivitySummary from(Activity a) {
        return new PortalActivitySummary(
                a.getId() == null ? null : a.getId().toString(),
                a.getType(),
                a.getDirection(),
                a.getSummary(),
                a.getBody(),
                a.getOccurredAt());
    }
}
