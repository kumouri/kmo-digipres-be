package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.audit.AuditEventRepository;
import com.kumouri.kmodigipresbe.audit.AuditOp;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.controller.EquipmentController;
import com.kumouri.kmodigipresbe.module.homeservices.model.Equipment;
import com.kumouri.kmodigipresbe.module.homeservices.service.EquipmentService;
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
 * Exercises the {@link EquipmentController#delete} path end-to-end through the
 * Reactor chain: a non-ADMIN tenant context errors with {@link DigiPresBeException}
 * {@code errorCode=1800, status=403}; an ADMIN context completes and writes an
 * {@link AuditEvent} of {@link AuditOp#DELETE}.
 *
 * <p>Calls the controller method directly rather than going through WebTestClient
 * + JWT login — RoleGuard reads from the Reactor Context, so a synthetic
 * {@link TenantContext} provided via {@code contextWrite} exercises the same code
 * path the JWT-resolved context would.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = "kmosf.modules.home-services.enabled=true")
class EquipmentDeleteRoleGuardIT {

    @Autowired EquipmentController controller;
    @Autowired EquipmentService service;
    @Autowired AuditEventRepository auditEvents;
    @Autowired TenantRepository tenants;
    @Autowired TenantModuleRegistry modules;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void seedTenant() {
        // Wipe — shared Mongo container.
        mongo.remove(new Query(), Equipment.class).block();
        mongo.remove(new Query(), AuditEvent.class).block();
        tenantId = UUID.randomUUID();
        // Tenant must exist with home-services in enabledModules so the
        // controller's TenantModuleRegistry.requireEnabled guard passes.
        tenants.save(Tenant.builder()
                        .id(tenantId)
                        .slug("rg-" + tenantId)
                        .displayName("RoleGuard IT")
                        .status(Tenant.TenantStatus.ACTIVE)
                        .enabledModules(Set.of(HomeServicesAutoConfiguration.MODULE_KEY))
                        .build())
                .block();
    }

    @Test
    void nonAdminDelete_403WithErrorCode1800() {
        UUID staffUser = UUID.randomUUID();
        TenantContext staffCtx = new TenantContext(tenantId, staffUser, Set.of("STAFF"));

        Equipment created = service.create(Equipment.builder()
                        .jobSiteId(UUID.randomUUID())
                        .serial("RG-1")
                        .equipmentType("HVAC")
                        .build())
                .contextWrite(TenantContextHolder.write(staffCtx))
                .block();
        assertThat(created).isNotNull();

        Throwable err = controller.delete(created.getId())
                .contextWrite(TenantContextHolder.write(staffCtx))
                .map(v -> (Throwable) null)
                .onErrorResume(t -> reactor.core.publisher.Mono.just(t))
                .block();
        assertThat(err).isInstanceOf(DigiPresBeException.class);
        DigiPresBeException dpb = (DigiPresBeException) err;
        assertThat(dpb.getErrorCode()).isEqualTo(1800);
        assertThat(dpb.getHttpStatusCode()).isEqualTo(403);

        // Row is still present.
        Equipment stillThere = service.findById(created.getId())
                .contextWrite(TenantContextHolder.write(staffCtx))
                .block();
        assertThat(stillThere).isNotNull();
    }

    @Test
    void adminDelete_completes_andWritesDeleteAuditEvent() {
        UUID adminUser = UUID.randomUUID();
        TenantContext adminCtx = new TenantContext(tenantId, adminUser, Set.of("STAFF", "ADMIN"));

        Equipment created = service.create(Equipment.builder()
                        .jobSiteId(UUID.randomUUID())
                        .serial("RG-2")
                        .equipmentType("HVAC")
                        .build())
                .contextWrite(TenantContextHolder.write(adminCtx))
                .block();
        assertThat(created).isNotNull();

        controller.delete(created.getId())
                .contextWrite(TenantContextHolder.write(adminCtx))
                .block();

        // Row is gone.
        Throwable readErr = service.findById(created.getId())
                .contextWrite(TenantContextHolder.write(adminCtx))
                .map(e -> (Throwable) null)
                .onErrorResume(t -> reactor.core.publisher.Mono.just(t))
                .block();
        assertThat(readErr).isInstanceOf(DigiPresBeException.class);

        // Audit DELETE event present, scoped to the right tenant + entity.
        List<AuditEvent> events = auditEvents
                .findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                        tenantId, created.getAuditEntityType(), created.getId())
                .collectList()
                .block();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getOp()).isEqualTo(AuditOp.DELETE);
        assertThat(events.get(0).getActorUserId()).isEqualTo(adminUser);
    }
}
