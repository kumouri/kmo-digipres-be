package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
public class WorkOrderService {

    private final WorkOrderRepository workOrders;
    private final ActivityRepository activities;
    private final RecurrenceExpansionService recurrence;
    private final JobSiteRepository jobSites;
    private final ContactRepository contacts;
    private final DomainEventPublisher events;
    private final WorkOrderNumberGenerator numbers;

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
        toCreate.setWorkOrderNumber(null); // server-assigned; never taken from the request body
        if (toCreate.getStatus() == null) toCreate.setStatus(WorkOrderStatus.DRAFT);
        return TenantContextHolder.required()
                .flatMap(ctx -> numbers.next(ctx.tenantId()))
                .map(number -> {
                    toCreate.setWorkOrderNumber(number);
                    return toCreate;
                })
                .flatMap(workOrders::save);
    }

    public Mono<WorkOrder> update(UUID id, WorkOrder patch) {
        return findById(id).flatMap(existing -> {
            WorkOrderStatus previousStatus = existing.getStatus();
            if (patch.getJobSiteId() != null) existing.setJobSiteId(patch.getJobSiteId());
            if (patch.getStatus() != null) existing.setStatus(patch.getStatus());
            if (patch.getScheduledStart() != null) existing.setScheduledStart(patch.getScheduledStart());
            if (patch.getScheduledEnd() != null) existing.setScheduledEnd(patch.getScheduledEnd());
            if (patch.getTechnicianUserId() != null) existing.setTechnicianUserId(patch.getTechnicianUserId());
            if (patch.getServiceType() != null) existing.setServiceType(patch.getServiceType());
            if (patch.getTitle() != null) existing.setTitle(patch.getTitle());
            if (patch.getRecurrenceRule() != null) existing.setRecurrenceRule(patch.getRecurrenceRule());
            if (patch.getNotes() != null) existing.setNotes(patch.getNotes());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return workOrders.save(existing)
                    .flatMap(saved -> maybeEmitEnRoute(previousStatus, saved).thenReturn(saved));
        });
    }

    /**
     * Phase 10e — emits {@link DomainEventType#WORK_ORDER_EN_ROUTE} when (and only
     * when) the WorkOrder's status has transitioned <em>into</em> {@code EN_ROUTE}:
     * the previous status was not {@code EN_ROUTE} and the new status is. A
     * save-with-no-status-change does not fire; nor does a save where {@code EN_ROUTE}
     * was already the previous value (re-saves should be idempotent w.r.t.
     * downstream automation).
     *
     * <p>Resolves {@code contactPhoneE164} by joining JobSite -> contactId ->
     * Contact.phones (picks the first {@code +}-prefixed number). When unresolvable,
     * the event still fires with {@code contactPhoneE164=null}; the
     * {@code RuleActionDispatcher}'s {@code SEND_SMS} branch already skips on a
     * missing/non-E.164 value, so emitting with null is safe and keeps the event
     * stream complete for other subscribers (audit, webhooks).
     */
    private Mono<Void> maybeEmitEnRoute(WorkOrderStatus previous, WorkOrder saved) {
        if (saved.getStatus() != WorkOrderStatus.EN_ROUTE || previous == WorkOrderStatus.EN_ROUTE) {
            return Mono.empty();
        }
        return resolveContactPhone(saved)
                .defaultIfEmpty("")
                .doOnNext(phone -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("workOrderId", saved.getId() == null ? null : saved.getId().toString());
                    payload.put("jobSiteId", saved.getJobSiteId() == null ? null : saved.getJobSiteId().toString());
                    payload.put("technicianUserId", saved.getTechnicianUserId() == null
                            ? null
                            : saved.getTechnicianUserId().toString());
                    payload.put("contactPhoneE164", phone.isBlank() ? null : phone);
                    events.publish(DomainEvent.of(
                            DomainEventType.WORK_ORDER_EN_ROUTE,
                            saved.getTenantId(),
                            saved.getId(),
                            payload));
                })
                .then();
    }

    /**
     * Best-effort lookup of the JobSite's primary contact phone. Returns an empty
     * Mono when the WorkOrder has no JobSite, the JobSite has no contactId, or the
     * Contact has no E.164 phone (first {@code +}-prefixed number wins).
     */
    private Mono<String> resolveContactPhone(WorkOrder wo) {
        if (wo.getJobSiteId() == null) return Mono.empty();
        return jobSites.findById(wo.getJobSiteId())
                .flatMap(site -> {
                    if (site.getContactId() == null) return Mono.<Contact>empty();
                    return contacts.findById(site.getContactId());
                })
                .flatMap(contact -> {
                    if (contact.getPhones() == null) return Mono.<String>empty();
                    for (PhoneNumber p : contact.getPhones()) {
                        if (p == null || p.number() == null) continue;
                        String n = p.number().trim();
                        if (n.startsWith("+") && n.length() >= 9) return Mono.just(n);
                    }
                    return Mono.<String>empty();
                })
                .onErrorResume(err -> {
                    log.warn("Failed to resolve contact phone for WorkOrder {}: {}",
                            wo.getId(), err.toString());
                    return Mono.empty();
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
