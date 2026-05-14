package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;

/**
 * Persists {@link AuditEvent}s. Used by {@link AuditingCallback} for CREATE/UPDATE
 * and by services that need to emit an explicit DELETE event (Spring Data MongoDB
 * has no reactive delete callback, so DELETEs are not captured automatically).
 *
 * <p>The {@link AuditEventRepository} is injected via {@link ObjectProvider} to break
 * the same bean-startup cycle that {@code CustomFieldValidator} fights:
 * {@link AuditingCallback} is a {@code ReactiveBeforeSaveCallback}, wired into the
 * {@code mappingMongoConverter} chain at context startup, and a direct dependency on
 * the repository would loop through {@code reactiveMongoTemplate} → converter →
 * callback. Lazy resolution defers the repository lookup until first invocation.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditEventWriter {

    private final ObjectProvider<AuditEventRepository> auditEvents;

    /**
     * Writes a CREATE/UPDATE event. Called by {@link AuditingCallback} from inside
     * the same Reactor chain as the entity save. On a write failure, logs and
     * swallows the error so the business save is not broken by an audit-log
     * blip.
     */
    public Mono<Void> writeChange(Auditable entity, AuditOp op, List<FieldDiff> diffs) {
        return write(entity, op, diffs);
    }

    /**
     * Records a DELETE for an Auditable entity. Call from a service <em>before</em>
     * issuing the {@code repo.deleteById(...)} so the audit reflects intent — if the
     * delete fails, the audit event is still useful evidence that someone tried.
     */
    public Mono<Void> auditDelete(Auditable entity) {
        return write(entity, AuditOp.DELETE, List.of());
    }

    private Mono<Void> write(Auditable entity, AuditOp op, List<FieldDiff> diffs) {
        return TenantContextHolder.current()
                .map(ctx -> AuditEvent.builder()
                        .tenantId(entity.getTenantId())
                        .actorUserId(ctx.userId())
                        .entityType(entity.getAuditEntityType())
                        .entityId(entity.getId())
                        .op(op)
                        .fieldDiffs(diffs == null ? List.of() : diffs)
                        .at(Instant.now())
                        .build())
                .switchIfEmpty(Mono.fromSupplier(() -> AuditEvent.builder()
                        .tenantId(entity.getTenantId())
                        .actorUserId(null)
                        .entityType(entity.getAuditEntityType())
                        .entityId(entity.getId())
                        .op(op)
                        .fieldDiffs(diffs == null ? List.of() : diffs)
                        .at(Instant.now())
                        .build()))
                .flatMap(evt -> auditEvents.getObject().save(evt))
                .doOnError(err -> log.warn(
                        "Failed to write audit event {}/{} {}",
                        entity.getAuditEntityType(), entity.getId(), op, err))
                .onErrorResume(err -> Mono.empty())
                .then();
    }
}
