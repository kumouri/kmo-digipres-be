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
import java.util.UUID;

/**
 * Append-only record of an authenticated HTTP access to the API — the read-side
 * complement to {@link AuditEvent} (which captures CREATE / UPDATE / DELETE of
 * entities). Written by {@link AccessAuditWebFilter} once per completed, tenant-scoped
 * request, satisfying the HIPAA Security Rule "audit controls" (45 CFR 164.312(b)) and
 * "information system activity review" (164.308(a)(1)(ii)(D)) requirement to record
 * <em>who read which record, when</em> — not only who changed it.
 *
 * <p>There is no update or delete API for access-audit events; retention is enforced by
 * a TTL index on {@link #at} (730 days, matching {@link AuditEvent}). Capture is OFF by
 * default and opt-in per deployment via {@code kmosf.audit.access-tracking.enabled}.
 *
 * <p>Like {@link AuditEvent}, this is {@link TenantScoped} but <em>not</em>
 * {@link Auditable} — auditing the access-audit write would recurse.
 */
@Document("access_audit_events")
@CompoundIndex(name = "acc_tenant_actor_at_idx",
        def = "{ 'tenantId': 1, 'actorUserId': 1, 'at': -1 }")
@CompoundIndex(name = "acc_tenant_resource_at_idx",
        def = "{ 'tenantId': 1, 'resourceType': 1, 'resourceId': 1, 'at': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AccessAuditEvent implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The authenticated principal who made the request (from the JWT, via TenantContext). */
    private UUID actorUserId;

    /** HTTP method (GET, POST, PUT, PATCH, DELETE). */
    private String method;

    /** Request path as seen by the filter (base-path stripped; includes the id for point reads). */
    private String path;

    /** First meaningful path segment (e.g. "contacts", "deals", "frontdesk"); null if not derivable. */
    private String resourceType;

    /** First id-looking (UUID) path segment; null for collection / list access. */
    private String resourceId;

    /** Final HTTP status of the response. */
    private int statusCode;

    /** WebFlux request id, for correlation with application logs. */
    private String requestId;

    /** Best-effort client address (may be a proxy / edge address). */
    private String remoteAddr;

    /**
     * When the access occurred. TTL-indexed — events older than 730 days are reaped,
     * matching {@link AuditEvent}. (A Stage-2 per-tenant retention policy will supersede.)
     */
    @Indexed(name = "acc_audit_at_ttl_idx", expireAfter = "730d")
    private Instant at;

    @CreatedDate
    private Instant createdAt;
}
