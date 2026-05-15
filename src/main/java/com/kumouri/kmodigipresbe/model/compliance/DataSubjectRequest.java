package com.kumouri.kmodigipresbe.model.compliance;

import com.kumouri.kmodigipresbe.audit.Auditable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * Tracks a GDPR data subject request (export or redact) for a single contact.
 *
 * <p>Async job pattern: the controller returns 202 + the job ID immediately;
 * the caller polls {@code GET /admin/dsr/{jobId}/status} until {@code status == DONE}
 * or {@code FAILED}.
 *
 * <p>For EXPORT requests, {@code resultUrl} holds a presigned download URL (S3 in
 * production; an inline-content URL for smaller deployments) valid for 24 hours
 * after completion.
 */
@Document("dsr_requests")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class DataSubjectRequest implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;
    private UUID contactId;

    private DsrType type;
    private DsrStatus status;

    /** Presigned download URL for EXPORT requests; null for REDACT. */
    private String resultUrl;

    private UUID actorUserId;

    private Instant requestedAt;
    private Instant completedAt;
    private String errorMessage;

    @CreatedDate
    private Instant createdAt;

    public enum DsrType { EXPORT, REDACT }

    public enum DsrStatus { PENDING, PROCESSING, DONE, FAILED }
}
