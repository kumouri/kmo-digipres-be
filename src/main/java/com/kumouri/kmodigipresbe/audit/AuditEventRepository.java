package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

public interface AuditEventRepository extends TenantScopedReactiveMongoRepository<AuditEvent, UUID> {

    Flux<AuditEvent> findAllByTenantIdAndEntityTypeAndEntityIdAndAtBetweenOrderByAtDesc(
            UUID tenantId, String entityType, UUID entityId, Instant from, Instant to);

    Flux<AuditEvent> findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
            UUID tenantId, String entityType, UUID entityId);

    Flux<AuditEvent> findAllByTenantIdAndActorUserIdAndAtBetweenOrderByAtDesc(
            UUID tenantId, UUID actorUserId, Instant from, Instant to);

    Flux<AuditEvent> findAllByTenantIdAndActorUserIdOrderByAtDesc(
            UUID tenantId, UUID actorUserId);
}
