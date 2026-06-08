package com.kumouri.kmodigipresbe.module.realestate.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;

import java.util.UUID;

/**
 * Repository for {@link HotHandoffLog} (RE-2). The unique {@code (tenantId, dealId)} index is the
 * idempotency guard — a duplicate insert (a re-fired HOT score for an already-handed-off Deal) throws
 * {@code DuplicateKeyException}, which {@code LeadHandoffService} swallows to {@code Mono.empty()}.
 */
public interface HotHandoffLogRepository
        extends TenantScopedReactiveMongoRepository<HotHandoffLog, UUID> {
}
