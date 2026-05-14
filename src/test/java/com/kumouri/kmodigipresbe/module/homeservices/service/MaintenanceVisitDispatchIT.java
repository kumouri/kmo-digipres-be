package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisitStatus;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
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

import java.time.Instant;
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
class MaintenanceVisitDispatchIT {

    @Autowired MaintenanceVisitService service;
    @Autowired MaintenanceVisitRepository visits;
    @Autowired WorkOrderRepository workOrders;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), MaintenanceVisit.class).block();
        mongo.remove(new Query(), WorkOrder.class).block();
        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void dispatch_createsScheduledWorkOrder_andStampsVisit() {
        UUID jobSiteId = UUID.randomUUID();
        Instant scheduledStart = Instant.parse("2026-07-01T13:00:00Z");
        MaintenanceVisit seeded = visits.save(MaintenanceVisit.builder()
                        .serviceAgreementId(UUID.randomUUID())
                        .jobSiteId(jobSiteId)
                        .scheduledStart(scheduledStart)
                        .status(MaintenanceVisitStatus.SCHEDULED)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(seeded).isNotNull();

        MaintenanceVisit dispatched = service.dispatch(seeded.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(dispatched).isNotNull();
        assertThat(dispatched.getStatus()).isEqualTo(MaintenanceVisitStatus.DISPATCHED);
        assertThat(dispatched.getWorkOrderId()).isNotNull();

        WorkOrder workOrder = workOrders.findById(dispatched.getWorkOrderId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(workOrder).isNotNull();
        assertThat(workOrder.getTenantId()).isEqualTo(tenantId);
        assertThat(workOrder.getJobSiteId()).isEqualTo(jobSiteId);
        assertThat(workOrder.getScheduledStart()).isEqualTo(scheduledStart);
        assertThat(workOrder.getStatus()).isEqualTo(WorkOrderStatus.SCHEDULED);
        assertThat(workOrder.getServiceType()).isEqualTo("maintenance-visit");
    }

    @Test
    void dispatchTwice_secondCall_409() {
        MaintenanceVisit seeded = visits.save(MaintenanceVisit.builder()
                        .serviceAgreementId(UUID.randomUUID())
                        .jobSiteId(UUID.randomUUID())
                        .scheduledStart(Instant.parse("2026-07-08T13:00:00Z"))
                        .status(MaintenanceVisitStatus.SCHEDULED)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(seeded).isNotNull();

        service.dispatch(seeded.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        StepVerifier.create(service.dispatch(seeded.getId())
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectError()
                .verify();
    }
}
