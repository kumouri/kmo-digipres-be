package com.kumouri.kmodigipresbe.module.homeservices.model;

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
 * A concrete occurrence materialized from a {@link ServiceAgreement}'s recurrence
 * rule. Dispatching a visit (10b) creates a {@code WorkOrder} in the field-service
 * module that the technician then runs through the standard pipeline.
 *
 * <p>The {@code tenant_agreement_start_idx} compound index is <strong>unique</strong>
 * so the scheduler can dedupe by (agreement, scheduledStart) and re-runs of the
 * 90-day window are idempotent.
 */
@Document("maintenance_visits")
@CompoundIndex(
        name = "tenant_agreement_start_idx",
        def = "{ 'tenantId': 1, 'serviceAgreementId': 1, 'scheduledStart': 1 }",
        unique = true)
@CompoundIndex(
        name = "tenant_status_start_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'scheduledStart': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MaintenanceVisit implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID serviceAgreementId;
    private UUID jobSiteId;

    private Instant scheduledStart;
    private Instant scheduledEnd;

    @Builder.Default
    private MaintenanceVisitStatus status = MaintenanceVisitStatus.SCHEDULED;

    /**
     * Populated by {@code MaintenanceVisitService.dispatch} when a WorkOrder is
     * created from this visit; null until then.
     */
    private UUID workOrderId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
