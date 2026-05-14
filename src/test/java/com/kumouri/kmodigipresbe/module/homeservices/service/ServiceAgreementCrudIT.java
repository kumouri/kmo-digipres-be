package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.BillingCadence;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreementStatus;
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
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true",
        "kmosf.home-services.scheduler.initial-delay-ms=86400000",
        "kmosf.files.region=us-east-1"
})
class ServiceAgreementCrudIT {

    @Autowired ServiceAgreementService service;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantA;
    private UUID tenantB;
    private TenantContext ctxA;
    private TenantContext ctxB;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), ServiceAgreement.class).block();
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();
        ctxA = new TenantContext(tenantA, UUID.randomUUID(), Set.of("STAFF"));
        ctxB = new TenantContext(tenantB, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void create_thenFindById_roundTrips() {
        ServiceAgreement saved = service.create(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("quarterly-hvac")
                        .startDate(LocalDate.of(2026, 1, 1))
                        .recurrenceRule("FREQ=MONTHLY;INTERVAL=3;COUNT=4")
                        .billingCadence(BillingCadence.QUARTERLY)
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(tenantA);
        assertThat(saved.getStatus()).isEqualTo(ServiceAgreementStatus.DRAFT);

        ServiceAgreement loaded = service.findById(saved.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAgreementType()).isEqualTo("quarterly-hvac");
    }

    @Test
    void update_patchesOnlyProvidedFields() {
        ServiceAgreement saved = service.create(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("quarterly-hvac")
                        .startDate(LocalDate.of(2026, 1, 1))
                        .recurrenceRule("FREQ=MONTHLY;INTERVAL=3;COUNT=4")
                        .billingCadence(BillingCadence.QUARTERLY)
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();

        ServiceAgreement patched = service.update(saved.getId(),
                        ServiceAgreement.builder().agreementType("annual-hvac").build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(patched).isNotNull();
        assertThat(patched.getAgreementType()).isEqualTo("annual-hvac");
        assertThat(patched.getRecurrenceRule())
                .as("unspecified fields preserved")
                .isEqualTo("FREQ=MONTHLY;INTERVAL=3;COUNT=4");
    }

    @Test
    void pauseAndActivate_transitionStatus() {
        ServiceAgreement saved = service.create(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("quarterly-hvac")
                        .startDate(LocalDate.of(2026, 1, 1))
                        .recurrenceRule("FREQ=MONTHLY;INTERVAL=3;COUNT=4")
                        .billingCadence(BillingCadence.QUARTERLY)
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();

        ServiceAgreement activated = service.activate(saved.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(activated).isNotNull();
        assertThat(activated.getStatus()).isEqualTo(ServiceAgreementStatus.ACTIVE);

        ServiceAgreement paused = service.pause(saved.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(paused).isNotNull();
        assertThat(paused.getStatus()).isEqualTo(ServiceAgreementStatus.PAUSED);
    }

    @Test
    void tenantB_cannotReadTenantA_agreements() {
        ServiceAgreement saved = service.create(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("quarterly-hvac")
                        .startDate(LocalDate.of(2026, 1, 1))
                        .recurrenceRule("FREQ=MONTHLY;INTERVAL=3;COUNT=4")
                        .billingCadence(BillingCadence.QUARTERLY)
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(saved).isNotNull();

        StepVerifier.create(service.findById(saved.getId())
                        .contextWrite(TenantContextHolder.write(ctxB)))
                .expectError()
                .verify();
    }
}
