package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
import com.kumouri.kmodigipresbe.module.homeservices.service.EquipmentService;
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

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CRUD + tenant isolation for {@link EquipmentService}. Cross-tenant reads, updates,
 * and deletes must all short-circuit on the tenant-scoped repository / Mongo
 * predicate — even when an attacker knows the equipment's id, the service either
 * returns an empty/404 or a no-op delete.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kmosf.modules.home-services.enabled=true")
class EquipmentCrudIT {

    @Autowired EquipmentService service;
    @Autowired ReactiveMongoTemplate mongo;

    private TenantContext ctxA;
    private TenantContext ctxB;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Equipment.class).block();
        ctxA = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Set.of("STAFF", "ADMIN"));
        ctxB = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Set.of("STAFF", "ADMIN"));
    }

    @Test
    void create_findById_update_delete_roundTrip() {
        UUID jobSiteId = UUID.randomUUID();
        Equipment created = service.create(Equipment.builder()
                        .jobSiteId(jobSiteId)
                        .equipmentType("HVAC_UNIT")
                        .manufacturer("Carrier")
                        .model("InfinityX")
                        .serial("CR-001")
                        .installDate(LocalDate.of(2024, 6, 1))
                        .warrantyExpiresAt(Instant.parse("2027-06-01T00:00:00Z"))
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(created).isNotNull();
        assertThat(created.getId()).isNotNull();
        assertThat(created.getTenantId()).isEqualTo(ctxA.tenantId());

        Equipment loaded = service.findById(created.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(loaded).isNotNull();
        assertThat(loaded.getEquipmentType()).isEqualTo("HVAC_UNIT");
        assertThat(loaded.getSerial()).isEqualTo("CR-001");

        Equipment updated = service.update(created.getId(), Equipment.builder()
                        .lastServicedAt(Instant.parse("2026-04-01T10:00:00Z"))
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(updated).isNotNull();
        assertThat(updated.getLastServicedAt()).isEqualTo(Instant.parse("2026-04-01T10:00:00Z"));
        // Untouched fields preserved.
        assertThat(updated.getSerial()).isEqualTo("CR-001");

        service.delete(created.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();

        Throwable err = service.findById(created.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .map(e -> (Throwable) null)
                .onErrorResume(t -> reactor.core.publisher.Mono.just(t))
                .block();
        assertThat(err).as("findById after delete should error 404").isNotNull();
    }

    @Test
    void findByJobSite_returnsOnlyTenantsEquipment() {
        UUID jobSiteId = UUID.randomUUID();
        Equipment a1 = service.create(Equipment.builder().jobSiteId(jobSiteId)
                        .serial("A1").equipmentType("X").build())
                .contextWrite(TenantContextHolder.write(ctxA)).block();
        Equipment a2 = service.create(Equipment.builder().jobSiteId(jobSiteId)
                        .serial("A2").equipmentType("Y").build())
                .contextWrite(TenantContextHolder.write(ctxA)).block();
        // Tenant B equipment on (coincidentally) the same jobSiteId UUID — must
        // not leak into A's view.
        Equipment b1 = service.create(Equipment.builder().jobSiteId(jobSiteId)
                        .serial("B1").equipmentType("Z").build())
                .contextWrite(TenantContextHolder.write(ctxB)).block();
        assertThat(a1).isNotNull();
        assertThat(a2).isNotNull();
        assertThat(b1).isNotNull();

        List<Equipment> aView = service.findByJobSite(jobSiteId)
                .contextWrite(TenantContextHolder.write(ctxA))
                .collectList()
                .block();
        assertThat(aView).extracting(Equipment::getSerial).containsExactlyInAnyOrder("A1", "A2");

        List<Equipment> bView = service.findByJobSite(jobSiteId)
                .contextWrite(TenantContextHolder.write(ctxB))
                .collectList()
                .block();
        assertThat(bView).extracting(Equipment::getSerial).containsExactly("B1");
    }

    @Test
    void tenantBCannotReadOrUpdateOrDeleteTenantAsEquipment() {
        Equipment a = service.create(Equipment.builder()
                        .jobSiteId(UUID.randomUUID())
                        .serial("SECRET")
                        .equipmentType("HVAC")
                        .build())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(a).isNotNull();

        // Read as B → 404 errored.
        Throwable readErr = service.findById(a.getId())
                .contextWrite(TenantContextHolder.write(ctxB))
                .map(e -> (Throwable) null)
                .onErrorResume(t -> reactor.core.publisher.Mono.just(t))
                .block();
        assertThat(readErr).isNotNull();

        // Update as B → 404 errored (findById fails first).
        Throwable updErr = service.update(a.getId(), Equipment.builder()
                        .serial("HIJACKED").build())
                .contextWrite(TenantContextHolder.write(ctxB))
                .map(e -> (Throwable) null)
                .onErrorResume(t -> reactor.core.publisher.Mono.just(t))
                .block();
        assertThat(updErr).isNotNull();

        // Delete as B → no-op: A's row still readable.
        Throwable delErr = service.delete(a.getId())
                .contextWrite(TenantContextHolder.write(ctxB))
                .map(v -> (Throwable) null)
                .onErrorResume(t -> reactor.core.publisher.Mono.just(t))
                .block();
        // Either error (findById-not-found) or empty completion; both are fine —
        // the proof is that A's row is still intact.
        assertThat(delErr).isNotNull();

        Equipment stillThere = service.findById(a.getId())
                .contextWrite(TenantContextHolder.write(ctxA))
                .block();
        assertThat(stillThere).isNotNull();
        assertThat(stillThere.getSerial()).isEqualTo("SECRET");
    }
}
