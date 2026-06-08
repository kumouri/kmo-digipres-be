package com.kumouri.kmodigipresbe.module.frontdesk.automation;

import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.automation.RuleAction;
import com.kumouri.kmodigipresbe.automation.WorkflowRule;
import com.kumouri.kmodigipresbe.automation.WorkflowRuleRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.lang.NonNull;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FrontDesk IQ (FD-2) — seeds an <strong>owner-tunable baseline reminder {@link WorkflowRule}</strong> for
 * every tenant with the {@code frontdesk} module enabled, the chairfill
 * {@link com.kumouri.kmodigipresbe.module.chairfill.automation.ChairFillReminderAutomation} analogue. Runs
 * once on {@link ApplicationReadyEvent}; idempotent on {@code (tenantId, name="}{@value #DEFAULT_RULE_NAME}
 * {@code ")} so a restart never duplicates.
 *
 * <p><strong>Why a no-op-friendly baseline alongside the dedicated subscriber:</strong> the real
 * risk-tiered confirmation (a Claude-personalized, F3-generic reminder for LOW/MEDIUM; an extra
 * confirmation ask for HIGH) is handled by {@link FrontDeskConfirmationService}, NOT by this rule — the
 * generic {@code SEND_SMS} action only resolves a <em>static</em> template. This rule exists so the owner
 * <em>sees a reminder rule they can toggle / edit in the admin</em> without the personalized logic
 * depending on it.
 *
 * <p><strong>v1 caveat (intentional):</strong> the FD-1 {@code APPOINTMENT_RISK_SCORED} payload carries
 * {@code contactId}/{@code providerId} but no resolved phone, so the generic {@code SEND_SMS} dispatcher's
 * {@code toPhoneField} lookup finds nothing and <em>safely skips</em> (logged, no throw — the documented
 * {@code RuleActionDispatcher} skip-on-missing-phone behavior). The personalized path (which resolves the
 * phone from the Contact itself) is the subscriber. The seeded rule is therefore a visible, editable
 * placeholder; the field is set to the established {@code contactPhoneE164} convention so it "just works"
 * if the payload is ever enriched.
 *
 * <p>Failure of one tenant's seed does not abort the others — the per-tenant pipeline catches + logs.
 */
@Slf4j
@RequiredArgsConstructor
public class FrontDeskReminderAutomation implements ApplicationListener<ApplicationReadyEvent> {

    public static final String DEFAULT_RULE_NAME = "frontdesk-risk-reminder-default";
    public static final String DEFAULT_TEMPLATE_NAME = "frontdesk-risk-reminder-default";
    public static final String PHONE_PAYLOAD_FIELD = "contactPhoneE164";

    private final TenantRepository tenants;
    private final WorkflowRuleRepository rules;

    @Override
    public void onApplicationEvent(@NonNull ApplicationReadyEvent event) {
        seedAll().subscribe(
                ignored -> {},
                err -> log.error("FrontDeskReminderAutomation seed failed", err));
    }

    /**
     * Visible-for-test entry — drives a deterministic seed without bouncing the context (the
     * {@code ChairFillReminderAutomation.seedAll()} pattern).
     */
    public Mono<Void> seedAll() {
        return tenants.findAll()
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(FrontDeskAutoConfiguration.MODULE_KEY))
                .concatMap(t -> seedForTenant(t.getId())
                        .onErrorResume(err -> {
                            log.warn("Failed to seed frontdesk reminder rule for tenant {}: {}",
                                    t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> seedForTenant(UUID tenantId) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_SEEDER"));
        return rules.findAllByTenantIdAndTriggerAndActive(
                        tenantId, DomainEventType.APPOINTMENT_RISK_SCORED, true)
                .filter(r -> DEFAULT_RULE_NAME.equals(r.getName()))
                .hasElements()
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("{} already present for tenant {}", DEFAULT_RULE_NAME, tenantId);
                        return Mono.<WorkflowRule>empty();
                    }
                    WorkflowRule rule = WorkflowRule.builder()
                            .name(DEFAULT_RULE_NAME)
                            .description("FrontDesk IQ FD-2 baseline: a toggleable reminder SMS when an "
                                    + "appointment is risk-scored. The personalized, PHI-free reminder + "
                                    + "HIGH-risk confirmation ask is handled by FrontDeskConfirmationService; "
                                    + "this rule is the owner-editable static baseline.")
                            .trigger(DomainEventType.APPOINTMENT_RISK_SCORED)
                            .conditions(List.of())
                            .actions(List.of(RuleAction.builder()
                                    .type(RuleAction.ActionType.SEND_SMS)
                                    .params(Map.of(
                                            "templateName", DEFAULT_TEMPLATE_NAME,
                                            "toPhoneField", PHONE_PAYLOAD_FIELD))
                                    .build()))
                            .active(true)
                            .build();
                    log.info("Seeding {} rule for tenant {}", DEFAULT_RULE_NAME, tenantId);
                    return rules.save(rule);
                })
                .contextWrite(TenantContextHolder.write(ctx))
                .then();
    }
}
