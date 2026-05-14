package com.kumouri.kmodigipresbe.model.inbox;

import com.kumouri.kmodigipresbe.audit.Auditable;
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
import java.util.UUID;

/**
 * Shared-inbox conversation grouping inbound messages by sender + normalised
 * subject. Phase 9g ships the data model + claim/reply API; IMAP-polling
 * ingest is wired here in a follow-up.
 */
@Document("inbox_threads")
@CompoundIndex(name = "tenant_status_last_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'lastMessageAt': -1 }")
@CompoundIndex(name = "tenant_from_subject_idx",
        def = "{ 'tenantId': 1, 'fromAddress': 1, 'subjectNormalized': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class InboxThread implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private String fromAddress;
    private String subjectNormalized;

    private Instant firstMessageAt;
    private Instant lastMessageAt;

    @Builder.Default
    private long messageCount = 0;

    @Builder.Default
    private Status status = Status.UNCLAIMED;

    private UUID claimedByUserId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { UNCLAIMED, CLAIMED, RESOLVED }
}
