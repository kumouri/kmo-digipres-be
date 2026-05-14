package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.homeservices.controller.dto.DispatchBoardResponseDTO;
import com.kumouri.kmodigipresbe.module.homeservices.controller.dto.DispatchBoardResponseDTO.SlotDTO;
import com.kumouri.kmodigipresbe.module.homeservices.controller.dto.DispatchBoardResponseDTO.TechnicianDayDTO;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only dispatch board: groups a tenant's {@link WorkOrder}s for a given
 * UTC day by technician, then by hour slot, ordering each tech's day greedily
 * by geographic proximity (nearest-neighbor from the first dispatch on the
 * board) using the {@link JobSite#getLocation() job-site coordinates}.
 *
 * <p>Work orders whose linked {@code JobSite} has no {@code location} fall back
 * to chronological ordering by {@code scheduledStart}. Work orders with no
 * assigned {@code technicianUserId} are bucketed under a single {@code null}
 * technician column so unassigned dispatches still surface on the board.
 */
@RequiredArgsConstructor
public class DispatchBoardService {

    private static final double EARTH_RADIUS_KM = 6371.0;

    private final ReactiveMongoOperations mongo;

    /**
     * Build a dispatch board for {@code date} (interpreted as a UTC day). When
     * {@code technicianId} is non-null, the board only contains that tech's
     * work orders.
     */
    public Mono<DispatchBoardResponseDTO> findForDate(LocalDate date, UUID technicianId) {
        Instant from = date.atStartOfDay(ZoneOffset.UTC).toInstant();
        Instant to = date.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant();
        return TenantContextHolder.required().flatMap(ctx -> {
            Criteria criteria = Criteria.where("tenantId").is(ctx.tenantId())
                    .and("scheduledStart").gte(from).lt(to);
            if (technicianId != null) {
                criteria = criteria.and("technicianUserId").is(technicianId);
            }
            return mongo.find(new Query(criteria), WorkOrder.class)
                    .collectList()
                    .flatMap(workOrders -> hydrateJobSites(workOrders)
                            .map(jobSites -> assemble(date, workOrders, jobSites)));
        });
    }

    private Mono<Map<UUID, JobSite>> hydrateJobSites(List<WorkOrder> workOrders) {
        List<UUID> ids = workOrders.stream()
                .map(WorkOrder::getJobSiteId)
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        if (ids.isEmpty()) {
            return Mono.just(Map.of());
        }
        return TenantContextHolder.required().flatMap(ctx -> mongo.find(
                        new Query(Criteria.where("_id").in(ids)
                                .andOperator(Criteria.where("tenantId").is(ctx.tenantId()))),
                        JobSite.class)
                .collectMap(JobSite::getId, js -> js));
    }

    private DispatchBoardResponseDTO assemble(LocalDate date,
                                              List<WorkOrder> workOrders,
                                              Map<UUID, JobSite> jobSites) {
        // 1. Group by technician (null bucket allowed for unassigned).
        Map<UUID, List<WorkOrder>> byTech = new LinkedHashMap<>();
        for (WorkOrder wo : workOrders) {
            byTech.computeIfAbsent(wo.getTechnicianUserId(), k -> new ArrayList<>()).add(wo);
        }

        List<TechnicianDayDTO> techDays = new ArrayList<>();
        for (Map.Entry<UUID, List<WorkOrder>> entry : byTech.entrySet()) {
            List<WorkOrder> techOrders = entry.getValue();
            // 2. Greedy nearest-neighbor reorder for this tech's day (within the
            // day; slot bucketing happens after the reorder so the chronologically
            // first dispatch still anchors hour-0 of the route).
            List<WorkOrder> routed = greedyRoute(techOrders, jobSites);
            // 3. Bucket by hour-of-day (UTC) within the tech's routed list.
            Map<Integer, List<WorkOrder>> bySlot = new LinkedHashMap<>();
            for (WorkOrder wo : routed) {
                int hour = wo.getScheduledStart() == null
                        ? 0
                        : wo.getScheduledStart().atZone(ZoneOffset.UTC).getHour();
                bySlot.computeIfAbsent(hour, k -> new ArrayList<>()).add(wo);
            }
            List<SlotDTO> slots = new ArrayList<>();
            bySlot.forEach((hour, list) -> slots.add(new SlotDTO(hour, list)));
            slots.sort(Comparator.comparingInt(SlotDTO::startHour));
            techDays.add(new TechnicianDayDTO(entry.getKey(), slots));
        }
        // Deterministic ordering across techs: null bucket last, then by UUID.
        techDays.sort(Comparator.comparing(
                TechnicianDayDTO::technicianUserId,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return new DispatchBoardResponseDTO(date, techDays);
    }

    /**
     * Greedy nearest-neighbor: anchor at the chronologically first dispatch
     * whose job site has coordinates, then repeatedly pick the closest unvisited
     * sibling. Work orders missing coordinates (no JobSite or no
     * {@code location}) fall to the end ordered by {@code scheduledStart}.
     */
    static List<WorkOrder> greedyRoute(List<WorkOrder> orders, Map<UUID, JobSite> jobSites) {
        if (orders.size() <= 1) {
            return new ArrayList<>(orders);
        }
        List<WorkOrder> withCoords = new ArrayList<>();
        List<WorkOrder> withoutCoords = new ArrayList<>();
        for (WorkOrder wo : orders) {
            if (coordsOf(wo, jobSites) != null) {
                withCoords.add(wo);
            } else {
                withoutCoords.add(wo);
            }
        }
        // Anchor: chronologically first dispatch with coords.
        Comparator<WorkOrder> byStart = Comparator.comparing(
                WorkOrder::getScheduledStart,
                Comparator.nullsLast(Comparator.naturalOrder()));
        withCoords.sort(byStart);
        withoutCoords.sort(byStart);

        List<WorkOrder> routed = new ArrayList<>();
        if (!withCoords.isEmpty()) {
            WorkOrder anchor = withCoords.remove(0);
            routed.add(anchor);
            LatLng current = coordsOf(anchor, jobSites);
            while (!withCoords.isEmpty()) {
                final LatLng cur = current;
                WorkOrder next = Collections.min(withCoords, Comparator.comparingDouble(
                        wo -> haversineKm(cur, coordsOf(wo, jobSites))));
                withCoords.remove(next);
                routed.add(next);
                current = coordsOf(next, jobSites);
            }
        }
        routed.addAll(withoutCoords);
        return routed;
    }

    private static LatLng coordsOf(WorkOrder wo, Map<UUID, JobSite> jobSites) {
        if (wo.getJobSiteId() == null) return null;
        JobSite js = jobSites.get(wo.getJobSiteId());
        if (js == null) return null;
        LatLng loc = js.getLocation();
        if (loc == null || loc.getCoordinates() == null || loc.getCoordinates().length < 2) {
            return null;
        }
        return loc;
    }

    private static double haversineKm(LatLng a, LatLng b) {
        if (a == null || b == null) return Double.MAX_VALUE;
        double lat1 = Math.toRadians(a.latitude());
        double lat2 = Math.toRadians(b.latitude());
        double dLat = lat2 - lat1;
        double dLng = Math.toRadians(b.longitude() - a.longitude());
        double sinDLat = Math.sin(dLat / 2);
        double sinDLng = Math.sin(dLng / 2);
        double h = sinDLat * sinDLat + Math.cos(lat1) * Math.cos(lat2) * sinDLng * sinDLng;
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.min(1.0, Math.sqrt(h)));
    }
}
