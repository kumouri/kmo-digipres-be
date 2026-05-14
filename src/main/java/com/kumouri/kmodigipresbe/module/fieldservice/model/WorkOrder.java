package com.kumouri.kmodigipresbe.module.fieldservice.model;

import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Document("work_orders")
@CompoundIndex(name = "tenant_status_start_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'scheduledStart': 1 }")
@CompoundIndex(name = "tenant_jobsite_idx",
        def = "{ 'tenantId': 1, 'jobSiteId': 1, 'scheduledStart': -1 }")
@CompoundIndex(name = "tenant_technician_idx",
        def = "{ 'tenantId': 1, 'technicianUserId': 1, 'scheduledStart': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WorkOrder implements TenantScoped, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID jobSiteId;

    @Builder.Default
    private WorkOrderStatus status = WorkOrderStatus.DRAFT;

    private Instant scheduledStart;
    private Instant scheduledEnd;

    private UUID technicianUserId;

    /**
     * Free-form service category — e.g. "MOLE_TRAPPING", "INITIAL_INSPECTION".
     * Tenants can constrain this with a custom field if they want to enforce
     * a vocabulary.
     */
    private String serviceType;

    /**
     * RFC 5545 RRULE string, e.g. {@code "FREQ=MONTHLY;BYMONTHDAY=15"}. Null for
     * one-off work orders. When set, this work order is the parent; instances
     * are materialised on demand by {@code RecurrenceExpansionService} and
     * reference back via {@link #parentWorkOrderId}.
     */
    private String recurrenceRule;

    private UUID parentWorkOrderId;

    private String notes;

    private Instant completedAt;
    private String completionSignatureRef;

    @Builder.Default
    private List<String> completionPhotoRefs = List.of();

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getEntityType() {
        return "WORK_ORDER";
    }
}
