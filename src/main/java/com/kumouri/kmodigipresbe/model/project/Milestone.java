package com.kumouri.kmodigipresbe.model.project;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A deliverable within a {@link Project}. When {@code triggersInvoiceOnComplete} is
 * {@code true}, completing this milestone creates a DRAFT {@link com.kumouri.kmodigipresbe.model.billing.Invoice}
 * via {@link com.kumouri.kmodigipresbe.service.billing.InvoiceService#create(com.kumouri.kmodigipresbe.model.billing.Invoice)}
 * (C-D8). The invoice is idempotent: {@code spawnedInvoiceId != null} means it has
 * already been spawned and no second invoice will be created.
 */
@Document("milestones")
@CompoundIndex(name = "tenant_project_idx", def = "{'tenantId':1,'projectId':1}")
@CompoundIndex(name = "tenant_status_idx",  def = "{'tenantId':1,'status':1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Milestone implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Required FK to the parent Project. */
    private UUID projectId;

    /** Required — service validates (errorCode 3411 if blank). */
    private String name;

    @Builder.Default
    private MilestoneStatus status = MilestoneStatus.PENDING;

    private LocalDate dueDate;

    /** Set on transition to COMPLETED. */
    private Instant completedAt;

    /**
     * When {@code true} and {@code spawnedInvoiceId == null}, completing this
     * milestone spawns a DRAFT invoice from {@code invoiceLineItems} (C-D8).
     */
    @Builder.Default
    private boolean triggersInvoiceOnComplete = false;

    /**
     * Line items used to build the spawned DRAFT invoice. Reuses the existing
     * {@link LineItem} shape so totals go through {@link com.kumouri.kmodigipresbe.model.quote.Quote#computeTotals()}
     * via {@link com.kumouri.kmodigipresbe.service.billing.InvoiceService#create}.
     */
    @Builder.Default
    private List<LineItem> invoiceLineItems = List.of();

    /**
     * Informational milestone value for display/sort. The invoice total is derived
     * from {@code invoiceLineItems}, not this field.
     */
    private BigDecimal amount;

    /**
     * Idempotency anchor — non-null means an invoice has already been spawned.
     * The completion logic checks this explicitly (C-D8) rather than using
     * {@code switchIfEmpty} (which would fire on any empty upstream).
     */
    private UUID spawnedInvoiceId;

    /** Ordering within a project. */
    @Builder.Default
    private int orderIndex = 0;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum MilestoneStatus {
        PENDING, IN_PROGRESS, COMPLETED
    }
}
