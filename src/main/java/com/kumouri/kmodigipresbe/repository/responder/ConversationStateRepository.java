package com.kumouri.kmodigipresbe.repository.responder;

import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Repository for the E2 {@link ConversationState} (one row per {@code (tenantId, phone)} thread).
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the router runs
 * outside a request context). {@code ConversationStateService.findOrCreate} uses this with an
 * <strong>explicit-boolean</strong> branch — never {@code switchIfEmpty(create)}.
 */
public interface ConversationStateRepository
        extends TenantScopedReactiveMongoRepository<ConversationState, UUID> {

    /** The thread for this tenant + phone, if any (the find-or-create probe). */
    Mono<ConversationState> findByTenantIdAndPhone(UUID tenantId, String phone);
}
