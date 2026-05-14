package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderRepository workOrders;
    private final ActivityRepository activities;
    private final RecurrenceExpansionService recurrence;

    public Flux<WorkOrder> findAll() {
        return workOrders.findAll();
    }

    public Mono<WorkOrder> findById(UUID id) {
        return workOrders.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "WorkOrder not found", 1330, 404)));
    }

    public Mono<WorkOrder> create(WorkOrder toCreate) {
        toCreate.setId(null);
        if (toCreate.getStatus() == null) toCreate.setStatus(WorkOrderStatus.DRAFT);
        return workOrders.save(toCreate);
    }

    public Mono<WorkOrder> update(UUID id, WorkOrder patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getJobSiteId() != null) existing.setJobSiteId(patch.getJobSiteId());
            if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
            if (patch.getScheduledStart() != null) existing.setScheduledStart(patch.getScheduledStart());
            if (patch.getScheduledEnd() != null) existing.setScheduledEnd(patch.getScheduledEnd());
            if (patch.getTechnicianUserId() != null) existing.setTechnicianUserId(patch.getTechnicianUserId());
            if (patch.getServiceType() != null) existing.setServiceType(patch.getServiceType());
            if (patch.getRecurrenceRule() != null) existing.setRecurrenceRule(patch.getRecurrenceRule());
            if (patch.getNotes() != null) existing.setNotes(patch.getNotes());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return workOrders.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return workOrders.deleteById(id);
    }

    public Flux<WorkOrder> upcoming(Instant from, Instant to) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> workOrders.findAllByTenantIdAndScheduledStartBetween(
                        ctx.tenantId(), from, to));
    }

    public Mono<WorkOrder> complete(UUID id, String signatureRef, List<String> photoRefs) {
        return findById(id).flatMap(wo -> {
            if (wo.getStatus().isTerminal()) {
                return Mono.error(new DigiPresBeException(
                        "WorkOrder is already terminal: " + wo.getStatus(), 1331, 409));
            }
            wo.setStatus(WorkOrderStatus.COMPLETED);
            wo.setCompletedAt(Instant.now());
            wo.setCompletionSignatureRef(signatureRef);
            wo.setCompletionPhotoRefs(photoRefs == null ? List.of() : List.copyOf(photoRefs));
            return workOrders.save(wo).flatMap(saved -> logCompletionActivity(saved).thenReturn(saved));
        });
    }

    private Mono<Activity> logCompletionActivity(WorkOrder wo) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("workOrderId", wo.getId().toString());
        payload.put("photoCount", wo.getCompletionPhotoRefs() == null ? 0 : wo.getCompletionPhotoRefs().size());
        payload.put("signed", wo.getCompletionSignatureRef() != null);
        return activities.save(Activity.builder()
                .type(ActivityType.TASK)
                .direction(ActivityDirection.INTERNAL)
                .subjectType(SubjectType.WORK_ORDER)
                .subjectId(wo.getId())
                .summary("Work order completed")
                .occurredAt(wo.getCompletedAt())
                .payload(payload)
                .build());
    }

    /**
     * Expand a recurring parent work order into concrete child instances inside a
     * window. Children are NOT persisted here — this is a read-only view.
     */
    public Flux<WorkOrder> expandRecurrence(UUID parentId, Instant from, Instant to) {
        return findById(parentId).flatMapMany(parent -> {
            if (parent.getRecurrenceRule() == null) {
                return Flux.just(parent);
            }
            List<Instant> instances = recurrence.expand(
                    parent.getRecurrenceRule(),
                    parent.getScheduledStart(),
                    from, to);
            return Flux.fromIterable(instances).map(start -> parent.toBuilder()
                    .id(null)
                    .parentWorkOrderId(parent.getId())
                    .scheduledStart(start)
                    .scheduledEnd(parent.getScheduledEnd() == null
                            ? null
                            : start.plus(java.time.Duration.between(
                                    parent.getScheduledStart(),
                                    parent.getScheduledEnd())))
                    .recurrenceRule(null)
                    .build());
        });
    }
}
