package com.kumouri.kmodigipresbe.module.homeservices.automation;

import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.automation.RuleAction;
import com.kumouri.kmodigipresbe.automation.WorkflowRule;
import com.kumouri.kmodigipresbe.automation.WorkflowRuleRepository;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Phase 10e — seeds the default on-the-way SMS workflow rule for every tenant
 * with the {@code home-services} module enabled. Runs once on
 * {@link ApplicationReadyEvent} so the rule is in place before any
 * {@link DomainEventType#WORK_ORDER_EN_ROUTE} event lands; idempotent on
 * {@code (tenantId, name="on-the-way-sms-default")} so a restart never duplicates.
 *
 * <p>Establishes synthetic {@link TenantContext} per tenant via
 * {@link TenantContextHolder#write} for the rule save — the seeder runs outside
 * any request, and the {@code WorkflowRule} collection is tenant-scoped via
 * {@link com.kumouri.kmodigipresbe.tenancy.TenantStampingCallback}.
 *
 * <p>Rule shape:
 * <ul>
 *   <li>trigger = {@code WORK_ORDER_EN_ROUTE}</li>
 *   <li>no conditions (fires on every WO->EN_ROUTE transition)</li>
 *   <li>one {@code SEND_SMS} action with
 *       {@code params={"templateName":"on-the-way-default","toPhoneField":"contactPhoneE164"}}</li>
 *   <li>active=true; tenants can disable per-rule via the workflow-rule admin
 *       endpoints without losing the seed</li>
 * </ul>
 *
 * <p>Failure of one tenant's seed does not abort the others — the per-tenant
 * pipeline catches and logs.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class OnTheWaySmsAutomation implements ApplicationListener<ApplicationReadyEvent> {

    public static final String DEFAULT_RULE_NAME = "on-the-way-sms-default";
    public static final String DEFAULT_TEMPLATE_NAME = "on-the-way-default";
    public static final String PHONE_PAYLOAD_FIELD = "contactPhoneE164";

    private final TenantRepository tenants;
    private final WorkflowRuleRepository rules;

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        seedAll().subscribe(
                ignored -> {},
                err -> log.error("OnTheWaySmsAutomation seed failed", err));
    }

    /**
     * Visible-for-test entry. Tests can call this directly to drive a deterministic
     * seed without bouncing the application context.
     */
    public Mono<Void> seedAll() {
        return tenants.findAll()
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(HomeServicesAutoConfiguration.MODULE_KEY))
                .concatMap(t -> seedForTenant(t.getId())
                        .onErrorResume(err -> {
                            log.warn("Failed to seed on-the-way rule for tenant {}: {}",
                                    t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> seedForTenant(UUID tenantId) {
        TenantContext ctx = new TenantContext(
                tenantId, null, Set.of("AUTOMATION_SEEDER"));
        return rules.findAllByTenantIdAndTriggerAndActive(
                        tenantId, DomainEventType.WORK_ORDER_EN_ROUTE, true)
                .filter(r -> DEFAULT_RULE_NAME.equals(r.getName()))
                .hasElements()
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("on-the-way-sms-default already present for tenant {}", tenantId);
                        return Mono.<WorkflowRule>empty();
                    }
                    WorkflowRule rule = WorkflowRule.builder()
                            .name(DEFAULT_RULE_NAME)
                            .description("Phase 10e default: send an SMS when a WorkOrder "
                                    + "transitions to EN_ROUTE.")
                            .trigger(DomainEventType.WORK_ORDER_EN_ROUTE)
                            .conditions(List.of())
                            .actions(List.of(RuleAction.builder()
                                    .type(RuleAction.ActionType.SEND_SMS)
                                    .params(Map.of(
                                            "templateName", DEFAULT_TEMPLATE_NAME,
                                            "toPhoneField", PHONE_PAYLOAD_FIELD))
                                    .build()))
                            .active(true)
                            .build();
                    log.info("Seeding on-the-way-sms-default rule for tenant {}", tenantId);
                    return rules.save(rule);
                })
                .contextWrite(TenantContextHolder.write(ctx))
                .then();
    }
}
