package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.homeservices.controller.dto.DispatchBoardResponseDTO;
import com.kumouri.kmodigipresbe.module.homeservices.service.DispatchBoardService;
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
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Field-service module also enabled so {@link JobSite} / {@link WorkOrder}
 * beans are present; the home-services {@link DispatchBoardService} only reads
 * them.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true",
        "kmosf.files.region=us-east-1"
})
class DispatchBoardIT {

    @Autowired DispatchBoardService service;
    @Autowired WorkOrderRepository workOrders;
    @Autowired JobSiteRepository jobSites;
    @Autowired ReactiveMongoTemplate mongo;

    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), WorkOrder.class).block();
        mongo.remove(new Query(), JobSite.class).block();
        ctx = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void groupsByTechnician_byHourSlot_andGeoOrdersWithinTheDay() {
        LocalDate date = LocalDate.of(2026, 5, 21);
        UUID techAlpha = UUID.randomUUID();
        UUID techBravo = UUID.randomUUID();

        // Tech ALPHA — three stops in a cluster around St. Louis, MO.
        // Coordinates (lat, lng): downtown STL (38.6270, -90.1994), Forest Park
        // (38.6357, -90.2843), Maplewood (38.6126, -90.3257). Chronologically the
        // 09:00 dispatch is downtown, 10:00 is Forest Park, 11:00 is Maplewood —
        // already in geographic order, so the greedy router should preserve it.
        JobSite jsDowntown = saveJobSite(jsId("alpha-downtown"), LatLng.of(38.6270, -90.1994));
        JobSite jsForestPark = saveJobSite(jsId("alpha-forestpark"), LatLng.of(38.6357, -90.2843));
        JobSite jsMaplewood = saveJobSite(jsId("alpha-maplewood"), LatLng.of(38.6126, -90.3257));

        // Save these out of geographic order (chronologically): 09:00 downtown,
        // 11:00 Maplewood (far), 10:00 Forest Park (between). The chronological
        // anchor is 09:00 downtown; greedy nearest-neighbor from there should
        // pick Forest Park (closer to downtown) before Maplewood.
        saveWorkOrder(techAlpha, jsDowntown.getId(), date.atTime(9, 0).toInstant(ZoneOffset.UTC));
        saveWorkOrder(techAlpha, jsMaplewood.getId(), date.atTime(11, 0).toInstant(ZoneOffset.UTC));
        saveWorkOrder(techAlpha, jsForestPark.getId(), date.atTime(10, 0).toInstant(ZoneOffset.UTC));

        // Tech BRAVO — two stops, simpler.
        JobSite jsBravo1 = saveJobSite(jsId("bravo-1"), LatLng.of(38.7000, -90.3000));
        JobSite jsBravo2 = saveJobSite(jsId("bravo-2"), LatLng.of(38.7100, -90.3100));
        saveWorkOrder(techBravo, jsBravo1.getId(), date.atTime(13, 0).toInstant(ZoneOffset.UTC));
        saveWorkOrder(techBravo, jsBravo2.getId(), date.atTime(14, 0).toInstant(ZoneOffset.UTC));

        DispatchBoardResponseDTO board = service.findForDate(date, null)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(board).isNotNull();
        assertThat(board.date()).isEqualTo(date);
        assertThat(board.technicians()).hasSize(2);

        // Find ALPHA's column.
        DispatchBoardResponseDTO.TechnicianDayDTO alpha = board.technicians().stream()
                .filter(t -> techAlpha.equals(t.technicianUserId()))
                .findFirst().orElseThrow();
        // Flatten slots in startHour order.
        List<WorkOrder> alphaRouted = alpha.slots().stream()
                .flatMap(s -> s.workOrders().stream())
                .toList();
        assertThat(alphaRouted).hasSize(3);
        // First stop = chronological anchor (downtown @ 09:00).
        assertThat(alphaRouted.get(0).getJobSiteId()).isEqualTo(jsDowntown.getId());
        // Second stop = geographically nearest to downtown = Forest Park (closer
        // than Maplewood), regardless of its 10:00 vs 11:00 scheduling.
        assertThat(alphaRouted.get(1).getJobSiteId()).isEqualTo(jsForestPark.getId());
        assertThat(alphaRouted.get(2).getJobSiteId()).isEqualTo(jsMaplewood.getId());

        // Slot hours are 9, 10, 11 (one work order per slot in this fixture).
        assertThat(alpha.slots()).extracting(DispatchBoardResponseDTO.SlotDTO::startHour)
                .containsExactly(9, 10, 11);
    }

    @Test
    void filterByTechnicianId_onlyReturnsThatTechsBoard() {
        LocalDate date = LocalDate.of(2026, 5, 22);
        UUID techAlpha = UUID.randomUUID();
        UUID techBravo = UUID.randomUUID();
        JobSite js = saveJobSite(UUID.randomUUID(), LatLng.of(38.6, -90.2));
        saveWorkOrder(techAlpha, js.getId(), date.atTime(9, 0).toInstant(ZoneOffset.UTC));
        saveWorkOrder(techBravo, js.getId(), date.atTime(10, 0).toInstant(ZoneOffset.UTC));

        DispatchBoardResponseDTO board = service.findForDate(date, techAlpha)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(board).isNotNull();
        assertThat(board.technicians()).hasSize(1);
        assertThat(board.technicians().get(0).technicianUserId()).isEqualTo(techAlpha);
    }

    @Test
    void nullLocation_fallsBackToScheduledStartOrdering() {
        LocalDate date = LocalDate.of(2026, 5, 23);
        UUID tech = UUID.randomUUID();
        // Two job sites, neither with location.
        JobSite js1 = saveJobSite(UUID.randomUUID(), null);
        JobSite js2 = saveJobSite(UUID.randomUUID(), null);
        // Insert chronologically later first to prove we don't just preserve
        // insertion order.
        saveWorkOrder(tech, js2.getId(), date.atTime(11, 0).toInstant(ZoneOffset.UTC));
        saveWorkOrder(tech, js1.getId(), date.atTime(9, 0).toInstant(ZoneOffset.UTC));

        DispatchBoardResponseDTO board = service.findForDate(date, null)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(board).isNotNull();
        assertThat(board.technicians()).hasSize(1);
        List<WorkOrder> routed = board.technicians().get(0).slots().stream()
                .flatMap(s -> s.workOrders().stream())
                .toList();
        assertThat(routed).hasSize(2);
        // Both lack coords, so fallback ordering is by scheduledStart: js1 @ 09:00
        // first, js2 @ 11:00 second.
        assertThat(routed.get(0).getJobSiteId()).isEqualTo(js1.getId());
        assertThat(routed.get(1).getJobSiteId()).isEqualTo(js2.getId());
    }

    @Test
    void filtersByUtcDayWindow_andTenant() {
        LocalDate date = LocalDate.of(2026, 5, 24);
        UUID tech = UUID.randomUUID();
        JobSite js = saveJobSite(UUID.randomUUID(), LatLng.of(38.0, -90.0));

        // In-window (UTC date).
        saveWorkOrder(tech, js.getId(), date.atTime(10, 0).toInstant(ZoneOffset.UTC));
        // Out of window — previous UTC day at 23:59.
        saveWorkOrder(tech, js.getId(),
                date.minusDays(1).atTime(23, 59).toInstant(ZoneOffset.UTC));
        // Out of window — next UTC day at 00:00.
        saveWorkOrder(tech, js.getId(), date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant());

        DispatchBoardResponseDTO board = service.findForDate(date, null)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(board).isNotNull();
        long totalDispatches = board.technicians().stream()
                .flatMap(t -> t.slots().stream())
                .mapToLong(s -> s.workOrders().size())
                .sum();
        assertThat(totalDispatches).isEqualTo(1);
    }

    private static UUID jsId(String marker) {
        // Use a content-stable UUID so test failures are easier to debug; the
        // bytes don't matter beyond uniqueness.
        return UUID.nameUUIDFromBytes(marker.getBytes());
    }

    private JobSite saveJobSite(UUID id, LatLng location) {
        JobSite js = JobSite.builder()
                .id(id)
                .label("test-site-" + id)
                .location(location)
                .build();
        JobSite saved = jobSites.save(js)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();
        return saved;
    }

    private WorkOrder saveWorkOrder(UUID technicianUserId, UUID jobSiteId, Instant scheduledStart) {
        WorkOrder wo = WorkOrder.builder()
                .jobSiteId(jobSiteId)
                .technicianUserId(technicianUserId)
                .scheduledStart(scheduledStart)
                .status(WorkOrderStatus.SCHEDULED)
                .build();
        WorkOrder saved = workOrders.save(wo)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(saved).isNotNull();
        return saved;
    }
}
