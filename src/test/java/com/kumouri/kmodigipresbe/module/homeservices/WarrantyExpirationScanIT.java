package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
import com.kumouri.kmodigipresbe.module.homeservices.repository.EquipmentRepository;
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
import reactor.core.Disposable;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Seeds three equipment rows with different warranty expirations across a
 * single tenant, plus one expiring-in-the-window row on a second tenant.
 * Subscribes to {@link DomainEventPublisher#stream()} <em>before</em>
 * triggering the scan, then asserts only the in-window rows fire and each
 * event carries the correct tenantId (proves the synthetic context per emission).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kmosf.modules.home-services.enabled=true")
class WarrantyExpirationScanIT {

    @Autowired EquipmentService service;
    @Autowired EquipmentRepository equipment;
    @Autowired DomainEventPublisher events;
    @Autowired ReactiveMongoTemplate mongo;

    private TenantContext ctxA;
    private TenantContext ctxB;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Equipment.class).block();
        ctxA = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Set.of("STAFF"));
        ctxB = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void scanEmitsOnlyForInWindowEquipment_andTagsTenantIdCorrectly() {
        Instant now = Instant.now();
        // Tenant A: one in-window (15d), one out-of-window (60d), one expired (5d ago).
        Equipment expiringA = saveAs(ctxA, Equipment.builder()
                .jobSiteId(UUID.randomUUID())
                .serial("A-15d")
                .warrantyExpiresAt(now.plus(Duration.ofDays(15)))
                .build());
        saveAs(ctxA, Equipment.builder()
                .jobSiteId(UUID.randomUUID())
                .serial("A-60d")
                .warrantyExpiresAt(now.plus(Duration.ofDays(60)))
                .build());
        saveAs(ctxA, Equipment.builder()
                .jobSiteId(UUID.randomUUID())
                .serial("A-expired")
                .warrantyExpiresAt(now.minus(Duration.ofDays(5)))
                .build());
        // Tenant B: one in-window — proves per-emission tenant context.
        Equipment expiringB = saveAs(ctxB, Equipment.builder()
                .jobSiteId(UUID.randomUUID())
                .serial("B-15d")
                .warrantyExpiresAt(now.plus(Duration.ofDays(15)))
                .build());

        List<DomainEvent> captured = new CopyOnWriteArrayList<>();
        Disposable sub = events.stream()
                .filter(e -> DomainEventType.EQUIPMENT_WARRANTY_EXPIRING.equals(e.type()))
                .subscribe(captured::add);
        try {
            service.scanWarranties().block();
            // Give multicast sink a beat to deliver to the subscriber.
            Thread.sleep(200);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } finally {
            sub.dispose();
        }

        assertThat(captured).hasSize(2);
        // Verify each in-window equipment id is represented, with the right tenant.
        assertThat(captured)
                .anySatisfy(e -> {
                    assertThat(e.tenantId()).isEqualTo(ctxA.tenantId());
                    assertThat(e.subjectId()).isEqualTo(expiringA.getId());
                    assertThat(e.payload()).containsEntry("equipmentId", expiringA.getId());
                    assertThat(e.payload()).containsEntry("serial", "A-15d");
                })
                .anySatisfy(e -> {
                    assertThat(e.tenantId()).isEqualTo(ctxB.tenantId());
                    assertThat(e.subjectId()).isEqualTo(expiringB.getId());
                    assertThat(e.payload()).containsEntry("serial", "B-15d");
                });
    }

    private Equipment saveAs(TenantContext ctx, Equipment partial) {
        Equipment saved = equipment.save(partial)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();
        return saved;
    }
}
