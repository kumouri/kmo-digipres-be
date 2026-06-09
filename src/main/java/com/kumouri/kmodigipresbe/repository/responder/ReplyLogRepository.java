package com.kumouri.kmodigipresbe.repository.responder;

import com.kumouri.kmodigipresbe.model.responder.ReplyLogEntry;
import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * Repository for the E2 {@link ReplyLogEntry} reply-cap ledger.
 *
 * <p>The derived finder carries an explicit {@code tenantId} predicate (the
 * {@link TenantScopedReactiveMongoRepository} marker does NOT auto-scope derived finders; the router runs
 * outside a request context). The rolling-window count gates the per-sender reply cap.
 */
public interface ReplyLogRepository
        extends TenantScopedReactiveMongoRepository<ReplyLogEntry, UUID> {

    /** How many replies this tenant has sent to {@code phone} since {@code after} (the rolling-day cap). */
    Mono<Long> countByTenantIdAndPhoneAndSentAtAfter(UUID tenantId, String phone, Instant after);
}
