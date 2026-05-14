package com.kumouri.kmodigipresbe.model.activity;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Document("activities")
@CompoundIndex(name = "tenant_subject_idx",
        def = "{ 'tenantId': 1, 'subjectType': 1, 'subjectId': 1, 'occurredAt': -1 }")
@CompoundIndex(name = "tenant_owner_due_idx",
        def = "{ 'tenantId': 1, 'ownerId': 1, 'dueAt': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Activity implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

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

    /**
     * Loosely typed payload for channel-specific fields. For EMAIL:
     * {@code from}, {@code to[]}, {@code messageId}, {@code threadId}, {@code rawHeaders}.
     * For CALL: {@code phoneNumber}, {@code durationSeconds}. For MEETING:
     * {@code meetingId} pointing at a {@link com.kumouri.kmodigipresbe.model.meeting.Meeting}.
     */
    @Builder.Default
    private Map<String, Object> payload = Map.of();

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
