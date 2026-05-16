package com.kumouri.kmodigipresbe.repository;

import com.kumouri.kmodigipresbe.model.idempotency.IdempotencyKey;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * System-level repository for idempotency-key records.
 *
 * <p>Extends bare {@link ReactiveMongoRepository} (NOT
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository})
 * because idempotency keys are a cross-tenant system collection. Multi-tenant isolation
 * is enforced by including {@code tenantId} in every lookup query.
 */
public interface IdempotencyKeyRepository extends ReactiveMongoRepository<IdempotencyKey, UUID> {

    /**
     * Look up a previously stored idempotency record by its composite key.
     *
     * @param tenantId      the tenant that made the original request
     * @param route         the route template (e.g. {@code "POST:/communication/singleEmail"})
     * @param idempotencyKey the client-supplied {@code Idempotency-Key} header value
     */
    Mono<IdempotencyKey> findByTenantIdAndRouteAndIdempotencyKey(
            UUID tenantId, String route, String idempotencyKey);
}
