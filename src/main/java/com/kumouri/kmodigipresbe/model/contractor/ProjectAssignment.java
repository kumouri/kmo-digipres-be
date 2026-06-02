package com.kumouri.kmodigipresbe.model.contractor;

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
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Joins a {@code User} (staff or contractor) to a Phase-C {@code Project}
 * (Phase J — contractor / time-management vertical).
 *
 * <p>A standalone join document — not an array on {@code Project} — because the
 * contractor's "list my assigned projects" is the highest-frequency authz query and
 * resolves to one indexed lookup on {@code (tenantId, userId)}. Per-assignment bill/cost
 * rate overrides live on the edge (the natural home for a project×person tuple) rather
 * than bloating the {@code Project} aggregate that every staff read already loads.
 *
 * <p>The unique {@code (tenantId, projectId, userId)} index makes assignment idempotent:
 * a duplicate create collides ({@code DuplicateKeyException}) and the service returns the
 * existing row (the {@code ProjectService.generateCodeAndSave} retry / {@code convertFromDeal}
 * 201-first / 200-repeat precedent).
 */
@Document("project_assignments")
@CompoundIndexes({
        @CompoundIndex(name = "tenant_user_idx",         def = "{'tenantId':1,'userId':1}"),
        @CompoundIndex(name = "tenant_project_idx",      def = "{'tenantId':1,'projectId':1}"),
        @CompoundIndex(name = "tenant_project_user_idx", def = "{'tenantId':1,'projectId':1,'userId':1}", unique = true)
})
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ProjectAssignment implements Auditable {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}. */
    private UUID tenantId;

    /** Required FK to Phase-C {@code Project} (errorCode 4001 if null). */
    private UUID projectId;

    /** Required FK to {@code User} (errorCode 4002 if null). */
    private UUID userId;

    /**
     * Nullable per-assignment client bill rate. When set, overrides
     * {@code User.defaultBillRate} as the {@code TimeEntry.rateAmount} stamped onto time
     * logged to this project by this user (J rate-resolution order).
     */
    private BigDecimal billRateOverride;

    /**
     * Nullable per-assignment contractor cost/pay rate. When set, overrides
     * {@code User.defaultCostRate} as the {@code TimeEntry.costRateAmount} stamp.
     */
    private BigDecimal costRateOverride;

    /** Nullable free-text role on this project (e.g. "Engineer"). String not enum (the {@code Expense.category} precedent). */
    private String role;

    /** Soft-unassign: {@code false} retires the assignment without losing rate history. */
    @Builder.Default
    private boolean active = true;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
