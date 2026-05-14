package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Append-only record of an Auditable entity's CREATE / UPDATE / DELETE. Written by
 * {@link AuditingCallback} (CREATE/UPDATE) or {@link AuditEventWriter#auditDelete}
 * (DELETE). There is no update or delete API for audit events; retention is enforced
 * by a TTL index on {@link #at} defaulted to 730 days (Phase 11 will extend with
 * per-tenant retention policies).
 *
 * <p>Note: AuditEvent itself is {@link TenantScoped} but <em>not</em> {@link Auditable}
 * — auditing an audit-event write would recurse infinitely.
 */
@Document("audit_events")
@CompoundIndex(name = "tenant_entity_at_idx",
        def = "{ 'tenantId': 1, 'entityType': 1, 'entityId': 1, 'at': -1 }")
@CompoundIndex(name = "tenant_actor_at_idx",
        def = "{ 'tenantId': 1, 'actorUserId': 1, 'at': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AuditEvent implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Null for system-initiated writes (webhook fan-out, scheduled jobs, anonymous widgets). */
    private UUID actorUserId;

    /** {@link Auditable#getAuditEntityType()} of the subject entity. */
    private String entityType;

    private UUID entityId;

    private AuditOp op;

    @Builder.Default
    private List<FieldDiff> fieldDiffs = List.of();

    /**
     * When the audit event was generated. TTL-indexed — events older than 730 days
     * are automatically reaped. Phase 11 retention policies will replace the fixed
     * TTL with a per-tenant policy.
     */
    @Indexed(name = "audit_at_ttl_idx", expireAfter = "730d")
    private Instant at;

    /** Optional correlation id; null until a request-id propagation hook lands. */
    private String requestId;

    @CreatedDate
    private Instant createdAt;
}
