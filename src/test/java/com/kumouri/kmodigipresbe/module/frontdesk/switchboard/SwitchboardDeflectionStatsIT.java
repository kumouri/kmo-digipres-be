package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4 (Health "Switchboard AI") — SwitchboardDeflectionStatsIT: the deflection analytics. Records
 * LOGISTICS + TRIPWIRE rows via {@link SwitchboardDeflectionService} and a HANDOFF row via the
 * {@link SwitchboardDeflectionRecorder} (a {@code RESPONDER_HANDED_OFF} event, health-scoped), then proves
 * the per-category counts + the deflection rate; and proves a NON-health tenant's handoff is NOT counted.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class SwitchboardDeflectionStatsIT {

    @Autowired SwitchboardDeflectionService deflection;
    @Autowired SwitchboardDeflectionRecorder recorder;
    @Autowired ResponderConfigRepository responderConfigs;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), SwitchboardDeflectionLog.class).block();
        mongo.remove(new Query(), ResponderConfig.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder().id(tenantId).slug("switchboard-stats-it-" + tenantId)
                .displayName("Switchboard Stats IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        // A health-vertical responder config makes the handoff recorder count this tenant's handoffs.
        responderConfigs.save(ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
                .vertical(SwitchboardIntents.VERTICAL)
                .intents(SwitchboardIntents.DEFAULTS)
                .build()).block();
    }

    @Test
    void recordsAndAggregatesPerCategory_withDeflectionRate() {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));

        // 3 logistics + 1 tripwire recorded inline (as the handlers would).
        deflection.record(tenantId, SwitchboardDeflectionCategory.LOGISTICS)
                .contextWrite(TenantContextHolder.write(ctx)).block();
        deflection.record(tenantId, SwitchboardDeflectionCategory.LOGISTICS)
                .contextWrite(TenantContextHolder.write(ctx)).block();
        deflection.record(tenantId, SwitchboardDeflectionCategory.LOGISTICS)
                .contextWrite(TenantContextHolder.write(ctx)).block();
        deflection.record(tenantId, SwitchboardDeflectionCategory.TRIPWIRE)
                .contextWrite(TenantContextHolder.write(ctx)).block();

        // 1 handoff via the event recorder (a RESPONDER_HANDED_OFF for this health tenant).
        Map<String, Object> payload = new HashMap<>();
        payload.put("phone", "+16185550450");
        payload.put("intent", "UNKNOWN");
        recorder.handle(DomainEvent.of(DomainEventType.RESPONDER_HANDED_OFF, tenantId, null, payload))
                .block();

        SwitchboardDeflectionStats stats = deflection.stats(tenantId).block();
        assertThat(stats.logistics()).isEqualTo(3);
        assertThat(stats.tripwire()).isEqualTo(1);
        assertThat(stats.handoff()).isEqualTo(1);
        assertThat(stats.total()).isEqualTo(5);
        // deflectionRate = logistics / total = 3/5 = 0.6
        assertThat(stats.deflectionRate()).isEqualTo(0.6);
    }

    @Test
    void handoffRecorder_ignoresNonHealthTenant() {
        // A second tenant with a realestate (non-health) responder config — its handoff must NOT count.
        UUID reTenant = UUID.randomUUID();
        mongo.save(Tenant.builder().id(reTenant).slug("switchboard-stats-re-" + reTenant)
                .displayName("RE tenant").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        responderConfigs.save(ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(reTenant).enabled(true)
                .vertical("realestate")
                .intents(SwitchboardIntents.DEFAULTS)
                .build()).block();

        Map<String, Object> payload = new HashMap<>();
        payload.put("phone", "+16185550460");
        payload.put("intent", "UNKNOWN");
        recorder.handle(DomainEvent.of(DomainEventType.RESPONDER_HANDED_OFF, reTenant, null, payload))
                .block();

        SwitchboardDeflectionStats stats = deflection.stats(reTenant).block();
        assertThat(stats.handoff()).as("a non-health tenant's handoff is not counted").isZero();
        assertThat(stats.total()).isZero();
    }
}
