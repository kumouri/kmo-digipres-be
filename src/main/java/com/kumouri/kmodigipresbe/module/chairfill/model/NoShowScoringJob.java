package com.kumouri.kmodigipresbe.module.chairfill.model;

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
 * Tracks the status of a manual or scheduled no-show-risk retrain run. The ChairFill
 * (CF-1) verbatim mirror of {@link com.kumouri.kmodigipresbe.model.ai.LeadScoringJob}:
 * returned to callers of {@code POST /chairfill/risk/retrain} so they can poll for
 * completion without blocking the HTTP response.
 */
@Document("chairfill_noshow_scoring_jobs")
@CompoundIndex(name = "tenant_status_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NoShowScoringJob implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    @Builder.Default
    private JobStatus status = JobStatus.PENDING;

    private int bookingsScored;
    private String errorMessage;

    private Instant startedAt;
    private Instant completedAt;

    @CreatedDate
    private Instant createdAt;

    public enum JobStatus { PENDING, RUNNING, DONE, FAILED }
}
