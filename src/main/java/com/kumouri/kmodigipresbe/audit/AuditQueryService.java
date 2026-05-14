package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditQueryService {

    /** Furthest-future BSON-safe timestamp ({@code 9999-12-31T23:59:59Z}). */
    private static final Instant DISTANT_FUTURE = Instant.ofEpochSecond(253_402_300_799L);

    private final AuditEventRepository repo;

    public Flux<AuditEvent> findForEntity(String entityType,
                                          UUID entityId,
                                          Instant from,
                                          Instant to,
                                          int limit) {
        Instant fromI = from != null ? from : Instant.EPOCH;
        Instant toI = to != null ? to : DISTANT_FUTURE;
        int cap = sanitizeLimit(limit);
        return TenantContextHolder.required()
                .flatMapMany(ctx -> repo
                        .findAllByTenantIdAndEntityTypeAndEntityIdAndAtBetweenOrderByAtDesc(
                                ctx.tenantId(), entityType, entityId, fromI, toI)
                        .take(cap));
    }

    public Flux<AuditEvent> findByActor(UUID actorUserId,
                                        Instant from,
                                        Instant to,
                                        int limit) {
        Instant fromI = from != null ? from : Instant.EPOCH;
        Instant toI = to != null ? to : DISTANT_FUTURE;
        int cap = sanitizeLimit(limit);
        return TenantContextHolder.required()
                .flatMapMany(ctx -> repo
                        .findAllByTenantIdAndActorUserIdAndAtBetweenOrderByAtDesc(
                                ctx.tenantId(), actorUserId, fromI, toI)
                        .take(cap));
    }

    private static int sanitizeLimit(int requested) {
        if (requested <= 0) {
            throw new DigiPresBeException("limit must be > 0", 1801, 400);
        }
        return Math.min(requested, 500);
    }
}
