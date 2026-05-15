package com.kumouri.kmodigipresbe.service.compliance;

import com.kumouri.kmodigipresbe.audit.AuditEventWriter;
import com.kumouri.kmodigipresbe.automation.RuleCondition;
import com.kumouri.kmodigipresbe.automation.condition.ConditionEvaluator;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.compliance.RetentionPolicy;
import com.kumouri.kmodigipresbe.model.inbox.InboxMessage;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.InboxMessageRepository;
import com.kumouri.kmodigipresbe.repository.RetentionPolicyRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RetentionPolicyService {

    static final String SYSTEM_ROLE = "RETENTION_PURGE";

    private final RetentionPolicyRepository retentionPolicyRepository;
    private final TenantRepository tenantRepository;
    private final ActivityRepository activityRepository;
    private final InboxMessageRepository inboxMessageRepository;
    private final AuditEventWriter auditEventWriter;

    // ── CRUD ─────────────────────────────────────────────────────────────────

    public Flux<RetentionPolicy> listPolicies(UUID tenantId) {
        return retentionPolicyRepository.findAllByTenantId(tenantId);
    }

    public Mono<RetentionPolicy> upsertPolicy(UUID tenantId, String entityType, int retentionDays,
                                              List<RuleCondition> exceptions) {
        if (retentionDays < 0) {
            return Mono.error(new DigiPresBeException("retentionDays must be 0 or positive", 3201, 400));
        }
        return retentionPolicyRepository.findByTenantIdAndEntityType(tenantId, entityType)
                .defaultIfEmpty(RetentionPolicy.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .entityType(entityType)
                        .build())
                .flatMap(existing -> retentionPolicyRepository.save(existing.toBuilder()
                        .retentionDays(retentionDays)
                        .exceptions(exceptions == null ? List.of() : exceptions)
                        .build()));
    }

    // ── Nightly purge ────────────────────────────────────────────────────────

    @Scheduled(cron = "${kmosf.retention.purge-cron:0 0 3 * * *}")
    public void nightlyPurge() {
        runPurge()
                .onErrorContinue((err, obj) -> log.warn("Retention purge error: {}", err.toString()))
                .subscribe();
    }

    public Mono<Void> runPurge() {
        return tenantRepository.findAll()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE)
                .flatMap(tenant -> {
                    TenantContext ctx = new TenantContext(tenant.getId(), null, Set.of(SYSTEM_ROLE));
                    return purgeForTenant(tenant.getId())
                            .contextWrite(TenantContextHolder.write(ctx));
                })
                .then();
    }

    private Mono<Void> purgeForTenant(UUID tenantId) {
        return retentionPolicyRepository.findAllByTenantId(tenantId)
                .filter(p -> p.getRetentionDays() > 0)
                .flatMap(policy -> purgeByPolicy(tenantId, policy))
                .then();
    }

    private Mono<Void> purgeByPolicy(UUID tenantId, RetentionPolicy policy) {
        Instant cutoff = Instant.now().minus(policy.getRetentionDays(), ChronoUnit.DAYS);
        return switch (policy.getEntityType()) {
            case "Activity" -> purgeActivities(tenantId, policy, cutoff);
            case "InboxMessage" -> purgeInboxMessages(tenantId, policy, cutoff);
            default -> {
                log.debug("No purge handler for entityType={}", policy.getEntityType());
                yield Mono.empty();
            }
        };
    }

    private Mono<Void> purgeActivities(UUID tenantId, RetentionPolicy policy, Instant cutoff) {
        return activityRepository.findAllByTenantIdAndCreatedAtBefore(tenantId, cutoff)
                .filter(a -> !isExempt(policy, a.getCustomFields()))
                .flatMap(a -> auditEventWriter.auditDelete(a)
                        .then(activityRepository.deleteByTenantIdAndId(a.getTenantId(), a.getId())))
                .count()
                .doOnNext(n -> log.info("Purged {} Activity docs for tenant {} (policy {}d)",
                        n, tenantId, policy.getRetentionDays()))
                .then();
    }

    private Mono<Void> purgeInboxMessages(UUID tenantId, RetentionPolicy policy, Instant cutoff) {
        return inboxMessageRepository.findAllByTenantIdAndCreatedAtBefore(tenantId, cutoff)
                .flatMap(m -> auditEventWriter.auditDelete(m)
                        .then(inboxMessageRepository.deleteByTenantIdAndId(m.getTenantId(), m.getId())))
                .count()
                .doOnNext(n -> log.info("Purged {} InboxMessage docs for tenant {} (policy {}d)",
                        n, tenantId, policy.getRetentionDays()))
                .then();
    }

    private boolean isExempt(RetentionPolicy policy, Map<String, Object> customFields) {
        if (policy.getExceptions() == null || policy.getExceptions().isEmpty()) return false;
        Map<String, Object> payload = customFields == null ? Map.of() : customFields;
        return ConditionEvaluator.matches(policy.getExceptions(), payload);
    }
}
