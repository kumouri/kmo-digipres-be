package com.kumouri.kmodigipresbe.module.homeservices.automation;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.automation.RuleAction;
import com.kumouri.kmodigipresbe.automation.WorkflowRule;
import com.kumouri.kmodigipresbe.automation.WorkflowRuleRepository;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10e — verifies {@link OnTheWaySmsAutomation} seeds the default
 * {@code on-the-way-sms-default} workflow rule for each tenant that has the
 * {@code home-services} module enabled, and that re-running the seeder is a
 * no-op (idempotent on the rule name).
 *
 * <p>The Spring application boots with home-services enabled, so the seeder
 * registers as a bean and runs {@code onApplicationEvent} once during context
 * startup. The test then asserts the rule exists, and re-invokes
 * {@link OnTheWaySmsAutomation#seedAll} directly to verify idempotency.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kmosf.modules.home-services.enabled=true")
class OnTheWayRuleSeedingIT {

    @Autowired OnTheWaySmsAutomation seeder;
    @Autowired TenantRepository tenants;
    @Autowired WorkflowRuleRepository rules;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), WorkflowRule.class).block();
        Tenant t = Tenant.builder()
                .id(UUID.randomUUID())
                .slug("seed-test-tenant")
                .displayName("Seed Test Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(HomeServicesAutoConfiguration.MODULE_KEY))
                .build();
        tenants.save(t).block();
        tenantId = t.getId();
    }

    @Test
    void seeder_createsDefaultRuleForHomeServicesTenant() {
        seeder.seedAll().block();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("TEST"));
        List<WorkflowRule> found = rules.findAllByTenantIdAndTriggerAndActive(
                        tenantId, DomainEventType.WORK_ORDER_EN_ROUTE, true)
                .contextWrite(TenantContextHolder.write(ctx))
                .collectList()
                .block();

        assertThat(found).isNotNull();
        assertThat(found).anySatisfy(rule -> {
            assertThat(rule.getName()).isEqualTo("on-the-way-sms-default");
            assertThat(rule.getTrigger()).isEqualTo(DomainEventType.WORK_ORDER_EN_ROUTE);
            assertThat(rule.getActions()).hasSize(1);
            RuleAction action = rule.getActions().get(0);
            assertThat(action.getType()).isEqualTo(RuleAction.ActionType.SEND_SMS);
            assertThat(action.getParams())
                    .containsEntry("templateName", "on-the-way-default")
                    .containsEntry("toPhoneField", "contactPhoneE164");
            assertThat(rule.isActive()).isTrue();
        });
    }

    @Test
    void seeder_isIdempotent_onSecondInvocation() {
        seeder.seedAll().block();
        seeder.seedAll().block();
        seeder.seedAll().block();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("TEST"));
        List<WorkflowRule> defaults = rules.findAllByTenantIdAndTriggerAndActive(
                        tenantId, DomainEventType.WORK_ORDER_EN_ROUTE, true)
                .filter(r -> "on-the-way-sms-default".equals(r.getName()))
                .contextWrite(TenantContextHolder.write(ctx))
                .collectList()
                .block();

        assertThat(defaults).hasSize(1);
    }

    @Test
    void seeder_skipsTenantsWithoutHomeServicesEnabled() {
        // Add a second tenant without home-services enabled.
        Tenant other = Tenant.builder()
                .id(UUID.randomUUID())
                .slug("other-test-tenant")
                .displayName("Other")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of())
                .build();
        tenants.save(other).block();

        seeder.seedAll().block();

        TenantContext ctx = new TenantContext(other.getId(), null, Set.of("TEST"));
        List<WorkflowRule> otherRules = rules.findAllByTenantIdAndTriggerAndActive(
                        other.getId(), DomainEventType.WORK_ORDER_EN_ROUTE, true)
                .contextWrite(TenantContextHolder.write(ctx))
                .collectList()
                .block();

        assertThat(otherRules).isEmpty();
    }
}
