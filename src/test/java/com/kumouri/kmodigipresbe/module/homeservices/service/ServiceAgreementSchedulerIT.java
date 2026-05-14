package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreementStatus;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
import com.kumouri.kmodigipresbe.module.homeservices.repository.ServiceAgreementRepository;
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

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true",
        "kmosf.files.region=us-east-1",
        // Disable the auto-tick so we control when materialization runs.
        "kmosf.home-services.scheduler.initial-delay-ms=86400000"
})
class ServiceAgreementSchedulerIT {

    @Autowired ServiceAgreementSchedulerService scheduler;
    @Autowired ServiceAgreementRepository agreements;
    @Autowired MaintenanceVisitRepository visits;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), ServiceAgreement.class).block();
        mongo.remove(new Query(), MaintenanceVisit.class).block();
        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void weeklyAgreement_materializesFourVisits_andRerunIsIdempotent() {
        // FREQ=WEEKLY;COUNT=4 starting today produces 4 occurrences within
        // the scheduler's 90-day window. Quarterly RRULEs only fit one
        // occurrence per window — they materialize across multiple ticks
        // over the year as the window slides forward.
        ServiceAgreement seeded = agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("weekly-bait-station")
                        .startDate(LocalDate.now())
                        .recurrenceRule("FREQ=WEEKLY;COUNT=4")
                        .status(ServiceAgreementStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(seeded).isNotNull();

        // First tick: 4 visits materialize.
        scheduler.runDueOnce().block();
        List<MaintenanceVisit> after1 = visits
                .findAllByTenantIdAndServiceAgreementId(tenantId, seeded.getId())
                .collectList()
                .block();
        assertThat(after1).hasSize(4);
        assertThat(after1).allSatisfy(v -> {
            assertThat(v.getTenantId()).isEqualTo(tenantId);
            assertThat(v.getServiceAgreementId()).isEqualTo(seeded.getId());
            assertThat(v.getScheduledStart()).isNotNull();
        });

        // Second tick: unique index dedupes; still 4 visits, same IDs.
        scheduler.runDueOnce().block();
        List<MaintenanceVisit> after2 = visits
                .findAllByTenantIdAndServiceAgreementId(tenantId, seeded.getId())
                .collectList()
                .block();
        assertThat(after2).hasSize(4);
        assertThat(after2.stream().map(MaintenanceVisit::getId).toList())
                .containsExactlyInAnyOrderElementsOf(
                        after1.stream().map(MaintenanceVisit::getId).toList());
    }

    @Test
    void nonActiveAgreement_isSkipped() {
        agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("paused-hvac")
                        .startDate(LocalDate.now())
                        .recurrenceRule("FREQ=WEEKLY;COUNT=4")
                        .status(ServiceAgreementStatus.PAUSED)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        scheduler.runDueOnce().block();

        List<MaintenanceVisit> after = visits.findAllByTenantIdAndStatus(
                        tenantId, com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisitStatus.SCHEDULED)
                .collectList()
                .block();
        assertThat(after).isEmpty();
    }

    @Test
    void badRrule_isLoggedAndSkipped_otherAgreementsStillMaterialize() {
        // Bad-RRULE agreement.
        agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("bad-rrule")
                        .startDate(LocalDate.now().minusDays(30))
                        .recurrenceRule("THIS-IS-NOT-AN-RRULE")
                        .status(ServiceAgreementStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        // Good-RRULE agreement, same tenant — weekly so all 4 occurrences
        // fit inside the 90-day scheduler window.
        ServiceAgreement good = agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("weekly-bait-station")
                        .startDate(LocalDate.now())
                        .recurrenceRule("FREQ=WEEKLY;COUNT=4")
                        .status(ServiceAgreementStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        scheduler.runDueOnce().block();

        List<MaintenanceVisit> goodVisits = visits
                .findAllByTenantIdAndServiceAgreementId(tenantId, good.getId())
                .collectList()
                .block();
        assertThat(goodVisits).hasSize(4);
    }
}
