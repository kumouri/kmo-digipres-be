package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.BillingCadence;
import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisitStatus;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreement;
import com.kumouri.kmodigipresbe.module.homeservices.model.ServiceAgreementStatus;
import com.kumouri.kmodigipresbe.module.homeservices.repository.EquipmentRepository;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
import com.kumouri.kmodigipresbe.module.homeservices.repository.ServiceAgreementRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persists one of each home-services entity through its repository under a
 * tenant context, reads it back, and verifies the compound indexes the entity
 * classes declare actually land in Mongo.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kmosf.modules.home-services.enabled=true")
class HomeServicesEntitiesRoundTripIT {

    @Autowired EquipmentRepository equipment;
    @Autowired ServiceAgreementRepository agreements;
    @Autowired MaintenanceVisitRepository visits;
    @Autowired ReactiveMongoTemplate mongo;

    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Equipment.class).block();
        mongo.remove(new Query(), ServiceAgreement.class).block();
        mongo.remove(new Query(), MaintenanceVisit.class).block();
        UUID tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void equipment_roundTrip() {
        UUID jobSiteId = UUID.randomUUID();
        Equipment saved = equipment.save(Equipment.builder()
                        .jobSiteId(jobSiteId)
                        .equipmentType("BAIT_STATION")
                        .manufacturer("Bell Labs")
                        .model("Protecta LP")
                        .serial("BL-001")
                        .installDate(LocalDate.of(2025, 4, 1))
                        .warrantyExpiresAt(Instant.parse("2027-04-01T00:00:00Z"))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getTenantId()).isEqualTo(ctx.tenantId());

        Equipment loaded = equipment.findById(saved.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getEquipmentType()).isEqualTo("BAIT_STATION");
        assertThat(loaded.getSerial()).isEqualTo("BL-001");
        assertThat(loaded.getInstallDate()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(loaded.getEntityType()).isEqualTo("EQUIPMENT");
    }

    @Test
    void serviceAgreement_roundTrip() {
        ServiceAgreement saved = agreements.save(ServiceAgreement.builder()
                        .contactId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .agreementType("quarterly-hvac")
                        .startDate(LocalDate.of(2026, 1, 1))
                        .recurrenceRule("FREQ=MONTHLY;INTERVAL=3;COUNT=4")
                        .billingCadence(BillingCadence.QUARTERLY)
                        .status(ServiceAgreementStatus.ACTIVE)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();

        ServiceAgreement loaded = agreements.findById(saved.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getAgreementType()).isEqualTo("quarterly-hvac");
        assertThat(loaded.getRecurrenceRule()).isEqualTo("FREQ=MONTHLY;INTERVAL=3;COUNT=4");
        assertThat(loaded.getBillingCadence()).isEqualTo(BillingCadence.QUARTERLY);
        assertThat(loaded.getStatus()).isEqualTo(ServiceAgreementStatus.ACTIVE);
        assertThat(loaded.getEntityType()).isEqualTo("SERVICE_AGREEMENT");
    }

    @Test
    void maintenanceVisit_roundTrip() {
        Instant start = Instant.now().plus(Duration.ofDays(7));
        MaintenanceVisit saved = visits.save(MaintenanceVisit.builder()
                        .serviceAgreementId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .scheduledStart(start)
                        .status(MaintenanceVisitStatus.SCHEDULED)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();

        MaintenanceVisit loaded = visits.findById(saved.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getScheduledStart()).isEqualTo(start);
        assertThat(loaded.getStatus()).isEqualTo(MaintenanceVisitStatus.SCHEDULED);
        assertThat(loaded.getWorkOrderId()).isNull();
    }

    @Test
    void maintenanceVisit_uniqueIndex_preventsDuplicateAgreementStart() {
        UUID agreementId = UUID.randomUUID();
        UUID jobSiteId = UUID.randomUUID();
        Instant start = Instant.parse("2026-06-01T13:00:00Z");

        visits.save(MaintenanceVisit.builder()
                        .serviceAgreementId(agreementId)
                        .jobSiteId(jobSiteId)
                        .scheduledStart(start)
                        .status(MaintenanceVisitStatus.SCHEDULED)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        Throwable error = visits.save(MaintenanceVisit.builder()
                        .serviceAgreementId(agreementId)
                        .jobSiteId(jobSiteId)
                        .scheduledStart(start)
                        .status(MaintenanceVisitStatus.SCHEDULED)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .map(v -> (Throwable) null)
                .onErrorResume(t -> reactor.core.publisher.Mono.just(t))
                .block();
        assertThat(error)
                .as("Second save of same (tenant, agreement, scheduledStart) must violate the unique index")
                .isNotNull();
    }

    @Test
    void compoundIndexes_areCreatedInMongo() {
        // Force index creation by issuing an empty find against each collection.
        mongo.find(new Query(), Equipment.class).collectList().block();
        mongo.find(new Query(), ServiceAgreement.class).collectList().block();
        mongo.find(new Query(), MaintenanceVisit.class).collectList().block();

        List<Document> equipmentIdx = mongo.getCollection("equipment")
                .flatMapMany(c -> reactor.core.publisher.Flux.from(c.listIndexes()))
                .collectList()
                .block();
        assertThat(indexNames(equipmentIdx)).contains("tenant_jobsite_idx", "tenant_warranty_idx");

        List<Document> agreementIdx = mongo.getCollection("service_agreements")
                .flatMapMany(c -> reactor.core.publisher.Flux.from(c.listIndexes()))
                .collectList()
                .block();
        assertThat(indexNames(agreementIdx)).contains("tenant_contact_idx", "tenant_status_idx");

        List<Document> visitIdx = mongo.getCollection("maintenance_visits")
                .flatMapMany(c -> reactor.core.publisher.Flux.from(c.listIndexes()))
                .collectList()
                .block();
        assertThat(indexNames(visitIdx))
                .contains("tenant_agreement_start_idx", "tenant_status_start_idx");
        Document uniqueIdx = visitIdx.stream()
                .filter(d -> "tenant_agreement_start_idx".equals(d.getString("name")))
                .findFirst()
                .orElseThrow();
        assertThat(uniqueIdx.getBoolean("unique", false)).isTrue();
    }

    private static List<String> indexNames(List<Document> indexes) {
        return indexes.stream().map(d -> d.getString("name")).toList();
    }
}
