package com.kumouri.kmodigipresbe.model.request;

import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ActivityDTO {
    private UUID id;
    private ActivityType type;
    private ActivityDirection direction;
    private SubjectType subjectType;
    private UUID subjectId;
    private String summary;
    private String body;
    private Instant occurredAt;
    private Instant dueAt;
    private Instant completedAt;
    private UUID ownerId;
    private Map<String, Object> payload;
    private Map<String, Object> customFields;
}
