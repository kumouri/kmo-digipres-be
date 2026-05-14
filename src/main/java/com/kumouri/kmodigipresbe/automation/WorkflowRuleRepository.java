package com.kumouri.kmodigipresbe.automation;

import com.kumouri.kmodigipresbe.tenancy.TenantScopedReactiveMongoRepository;
import reactor.core.publisher.Flux;

import java.util.UUID;

public interface WorkflowRuleRepository
        extends TenantScopedReactiveMongoRepository<WorkflowRule, UUID> {

    /**
     * Rules to evaluate for an incoming event. Filters on (tenantId, trigger, active=true).
     * Called from {@link RuleEngine} OUTSIDE a request context — the engine establishes
     * tenant context explicitly per event.
     */
    Flux<WorkflowRule> findAllByTenantIdAndTriggerAndActive(
            UUID tenantId, String trigger, boolean active);
}
