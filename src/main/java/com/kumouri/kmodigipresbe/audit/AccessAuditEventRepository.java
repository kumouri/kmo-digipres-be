package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

/**
 * Append-only repository for {@link AccessAuditEvent}. Mirrors {@link AuditEventRepository}:
 * tenant-scoped finders for later examination ("information system activity review"). No
 * update / delete is exposed in application code; the TTL index reaps old events.
 */
public interface AccessAuditEventRepository
        extends TenantScopedReactiveMongoRepository<AccessAuditEvent, UUID> {

    Flux<AccessAuditEvent> findAllByTenantIdAndActorUserIdOrderByAtDesc(
            UUID tenantId, UUID actorUserId);

    Flux<AccessAuditEvent> findAllByTenantIdAndResourceTypeAndResourceIdOrderByAtDesc(
            UUID tenantId, String resourceType, String resourceId);

    Flux<AccessAuditEvent> findAllByTenantIdAndAtBetweenOrderByAtDesc(
            UUID tenantId, Instant from, Instant to);
}
