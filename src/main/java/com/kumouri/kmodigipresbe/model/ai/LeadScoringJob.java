package com.kumouri.kmodigipresbe.model.ai;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
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
 * Tracks the status of a manual or scheduled lead-scoring retrain run.
 * Returned to callers of {@code POST /admin/lead-scoring/retrain} so they can
 * poll for completion without blocking the HTTP response.
 */
@Document("lead_scoring_jobs")
@CompoundIndex(name = "tenant_status_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LeadScoringJob implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    private int contactsScored;
    private String errorMessage;

    private Instant startedAt;
    private Instant completedAt;

    @CreatedDate
    private Instant createdAt;

    public enum JobStatus { PENDING, RUNNING, DONE, FAILED }
}
