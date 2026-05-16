package com.kumouri.kmodigipresbe.model.idempotency;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * Persistent record of an idempotency key for a mutation request.
 *
 * <p>System collection — not tenant-scoped (does NOT extend
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScoped} or implement
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}). Multi-tenant isolation is
 * enforced by including {@code tenantId} as part of the composite key.
 *
 * <h2>Key design</h2>
 * <ul>
 *   <li>{@code tenantId} + {@code route} + {@code idempotencyKey} form the unique
 *       composite key ({@code CompoundIndex}, unique).  This prevents key collisions
 *       between tenants and between different routes within the same tenant.</li>
 *   <li>{@code route} is the route template (e.g. {@code "POST:/communication/singleEmail"}),
 *       not the raw URI — path variables must not fragment idempotency keys.</li>
 *   <li>24-hour TTL via {@code @Indexed(expireAfterSeconds = 86400)} on
 *       {@code createdAt} (requires {@code spring.data.mongodb.auto-index-creation=true}
 *       or manual index creation in prod).</li>
 * </ul>
 *
 * <h2>Replay payload</h2>
 * The response body is Base64-encoded so arbitrary binary payloads round-trip through
 * MongoDB's UTF-8 strings. Only HTTP 2xx responses are persisted; error responses are
 * never cached so retries always reach the handler.
 *
 * <h2>Error codes (owned by Phase A)</h2>
 * <ul>
 *   <li>{@code 3100} — {@code Idempotency-Key} header missing on an {@link IdempotentRoute}-annotated endpoint</li>
 *   <li>{@code 3101} — concurrent duplicate key race (unique index violation on first insert)</li>
 * </ul>
 */
@Document("idempotency_keys")
@CompoundIndex(
        name = "tenant_route_key_idx",
        def = "{ 'tenantId': 1, 'route': 1, 'idempotencyKey': 1 }",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class IdempotencyKey {

    @Id
    private UUID id;

    /** Tenant that made the request (from JWT; part of composite key). */
    private UUID tenantId;

    /**
     * Route template, e.g. {@code "POST:/communication/singleEmail"}.
     * Uses the best-matching pattern from {@code RequestMappingHandlerMapping},
     * not the raw URI, so path variables don't fragment the key space.
     */
    private String route;

    /** Client-supplied idempotency key (from the {@code Idempotency-Key} header). */
    private String idempotencyKey;

    /** HTTP status code of the original response. */
    private int responseStatus;

    /**
     * Response body, Base64-encoded. Stored only for 2xx responses.
     * Null if the original handler returned no body.
     */
    private String responseBodyBase64;

    /** {@code Content-Type} of the original response (e.g. {@code application/json}). */
    private String responseContentType;

    /**
     * Creation timestamp. TTL index expires this document after 24 hours
     * ({@code expireAfterSeconds = 86400}).
     */
    @Indexed(expireAfterSeconds = 86400)
    private Instant createdAt;
}
