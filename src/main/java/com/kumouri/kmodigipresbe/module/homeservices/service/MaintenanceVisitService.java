package com.kumouri.kmodigipresbe.module.homeservices.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit;
import com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisitStatus;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@RequiredArgsConstructor
public class MaintenanceVisitService {

    private static final String DISPATCHED_SERVICE_TYPE = "maintenance-visit";

    private final MaintenanceVisitRepository visits;
    private final WorkOrderService workOrders;

    public Flux<MaintenanceVisit> findAll() {
        return visits.findAll();
    }

    public Mono<MaintenanceVisit> findById(UUID id) {
        return visits.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "MaintenanceVisit not found", 2720, 404)));
    }

    public Flux<MaintenanceVisit> findByAgreement(UUID serviceAgreementId) {
        return TenantContextHolder.required().flatMapMany(ctx ->
                visits.findAllByTenantIdAndServiceAgreementId(ctx.tenantId(), serviceAgreementId));
    }

    public Flux<MaintenanceVisit> findInRange(Instant from, Instant to) {
        return TenantContextHolder.required().flatMapMany(ctx ->
                visits.findAllByTenantIdAndScheduledStartBetween(ctx.tenantId(), from, to));
    }

    public Mono<Void> delete(UUID id) {
        return visits.deleteById(id);
    }

    /**
     * Materialize a {@link MaintenanceVisit} into the field-service pipeline:
     * create a {@code WorkOrder} pre-scheduled at the visit's
     * {@code scheduledStart}, link it back via {@code visit.workOrderId}, and
     * mark the visit {@code DISPATCHED}.
     */
    public Mono<MaintenanceVisit> dispatch(UUID id) {
        return findById(id).flatMap(visit -> {
            if (visit.getStatus() == MaintenanceVisitStatus.DISPATCHED) {
                return Mono.error(new DigiPresBeException(
                        "MaintenanceVisit is already dispatched", 2721, 409));
            }
            if (visit.getStatus() == MaintenanceVisitStatus.COMPLETED) {
                return Mono.error(new DigiPresBeException(
                        "MaintenanceVisit is already completed", 2722, 409));
            }
            if (visit.getStatus() == MaintenanceVisitStatus.CANCELLED) {
                return Mono.error(new DigiPresBeException(
                        "Cancelled MaintenanceVisit cannot be dispatched", 2723, 409));
            }
            WorkOrder seed = WorkOrder.builder()
                    .jobSiteId(visit.getJobSiteId())
                    .scheduledStart(visit.getScheduledStart())
                    .scheduledEnd(visit.getScheduledEnd())
                    .status(WorkOrderStatus.SCHEDULED)
                    .serviceType(DISPATCHED_SERVICE_TYPE)
                    .build();
            return workOrders.create(seed).flatMap(workOrder -> {
                visit.setStatus(MaintenanceVisitStatus.DISPATCHED);
                visit.setWorkOrderId(workOrder.getId());
                return visits.save(visit);
            });
        });
    }
}
