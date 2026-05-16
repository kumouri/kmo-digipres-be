package com.kumouri.kmodigipresbe.service.project;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.project.Milestone;
import com.kumouri.kmodigipresbe.model.project.Milestone.MilestoneStatus;
import com.kumouri.kmodigipresbe.repository.project.MilestoneRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the {@link Milestone} lifecycle (C-D2, C-D8).
 *
 * <p>On completion, if {@code triggersInvoiceOnComplete == true} and
 * {@code spawnedInvoiceId == null}, a DRAFT Invoice is spawned via the
 * <strong>existing</strong> {@link InvoiceService#create(Invoice)} — which sets id null,
 * recomputes totals via {@code Quote.computeTotals()}, and saves as DRAFT without
 * emitting {@code INVOICE_FINALIZED}. This is exactly the behaviour the plan mandates
 * (ultraplan §10 Phase C verification bullet + decision D10).
 *
 * <p><strong>Idempotency (C-D8 / §9 item 2):</strong> the {@code spawnedInvoiceId}
 * field is the anchor. The check is an explicit {@code if (spawnedInvoiceId != null) skip
 * else spawn} — NEVER {@code switchIfEmpty(spawn)}, which would fire whenever the upstream
 * completes empty and could double-create. AC-C5 is the regression test for this contract.
 */
@Service
@RequiredArgsConstructor
public class MilestoneService {

    private static final Set<String> ILLEGAL_TRANSITIONS = Set.of(
            "COMPLETED->PENDING",
            "COMPLETED->IN_PROGRESS"
    );

    private final MilestoneRepository milestones;
    private final ProjectRepository projects;
    private final InvoiceService invoiceService;
    private final DomainEventPublisher events;

    public Flux<Milestone> findByProject(UUID projectId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> milestones.findAllByTenantIdAndProjectIdOrderByOrderIndexAsc(
                        ctx.tenantId(), projectId));
    }

    public Mono<Milestone> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> milestones.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Milestone not found", 3410, 404)));
    }

    public Mono<Milestone> create(UUID projectId, Milestone body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getName() == null || body.getName().isBlank()) {
                return Mono.error(new DigiPresBeException("Milestone name is required", 3411, 400));
            }
            body.setId(null);
            body.setTenantId(ctx.tenantId());
            body.setProjectId(projectId);
            if (body.getStatus() == null) {
                body.setStatus(MilestoneStatus.PENDING);
            }
            body.setSpawnedInvoiceId(null);
            return milestones.save(body);
        });
    }

    public Mono<Milestone> update(UUID id, Milestone patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) {
                if (patch.getName().isBlank()) {
                    return Mono.error(new DigiPresBeException("Milestone name is required", 3411, 400));
                }
                existing.setName(patch.getName());
            }
            if (patch.getDueDate() != null) existing.setDueDate(patch.getDueDate());
            if (patch.getAmount() != null) existing.setAmount(patch.getAmount());
            if (patch.getInvoiceLineItems() != null) existing.setInvoiceLineItems(patch.getInvoiceLineItems());
            existing.setTriggersInvoiceOnComplete(patch.isTriggersInvoiceOnComplete());
            existing.setOrderIndex(patch.getOrderIndex());
            return milestones.save(existing);
        });
    }

    /**
     * Transitions a milestone's status. If transitioning to COMPLETED and
     * {@code triggersInvoiceOnComplete == true} and {@code spawnedInvoiceId == null},
     * spawns a DRAFT invoice via {@link InvoiceService#create(Invoice)}.
     *
     * <p>The idempotency guard is an explicit {@code if} check on the boolean value
     * of {@code spawnedInvoiceId != null} — not {@code switchIfEmpty} or any reactive
     * combinator that would fire on an empty upstream. This is the §9 item 2 contract.
     */
    public Mono<Milestone> transition(UUID id, MilestoneStatus newStatus) {
        return TenantContextHolder.required().flatMap(ctx ->
                milestones.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Milestone not found", 3410, 404)))
                        .flatMap(existing -> {
                            String transitionKey = existing.getStatus().name() + "->" + newStatus.name();
                            if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                                return Mono.error(new DigiPresBeException(
                                        "Transition " + transitionKey + " is not permitted", 3412, 409));
                            }
                            existing.setStatus(newStatus);
                            if (newStatus == MilestoneStatus.COMPLETED && existing.getCompletedAt() == null) {
                                existing.setCompletedAt(Instant.now());
                            }
                            return milestones.save(existing)
                                    .flatMap(saved -> maybeSpawnInvoice(ctx.tenantId(), saved));
                        }));
    }

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(m -> milestones.deleteById(m.getId()));
    }

    /**
     * Spawns a DRAFT invoice if the milestone is COMPLETED, has
     * {@code triggersInvoiceOnComplete == true}, and has NOT already spawned one
     * ({@code spawnedInvoiceId == null}).
     *
     * <p><strong>Idempotency — explicit boolean check, NOT switchIfEmpty:</strong>
     * {@code if (saved.getSpawnedInvoiceId() != null)} returns the milestone as-is.
     * This ensures a COMPLETED→IN_PROGRESS→COMPLETED bounce or a double-click on
     * "Complete" does not spawn a second invoice (AC-C5).
     */
    private Mono<Milestone> maybeSpawnInvoice(UUID tenantId, Milestone saved) {
        if (saved.getStatus() != MilestoneStatus.COMPLETED
                || !saved.isTriggersInvoiceOnComplete()) {
            // Either not completing or not invoice-triggering — publish event and return
            return publishMilestoneCompleted(tenantId, saved, null).thenReturn(saved);
        }

        // Explicit boolean check — NOT switchIfEmpty (§9 item 2 / C-D8)
        if (saved.getSpawnedInvoiceId() != null) {
            // Already spawned — idempotent no-op; do not error, do not spawn again
            return Mono.just(saved);
        }

        // Load the parent project for context fields (dealId, contactId, companyId)
        return projects.findByTenantIdAndId(tenantId, saved.getProjectId())
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Project not found", 3400, 404)))
                .flatMap(project -> {
                    Invoice invoice = Invoice.builder()
                            .tenantId(tenantId)
                            .projectId(project.getId())
                            .milestoneId(saved.getId())
                            .dealId(project.getDealId())
                            .contactId(project.getPrimaryContactId())
                            .companyId(project.getCompanyId())
                            .currency("USD")
                            .lineItems(saved.getInvoiceLineItems())
                            .status(Invoice.Status.DRAFT)
                            .build();
                    return invoiceService.create(invoice)
                            .flatMap(createdInvoice -> {
                                // Persist the idempotency anchor
                                saved.setSpawnedInvoiceId(createdInvoice.getId());
                                return milestones.save(saved)
                                        .flatMap(milestoneWithRef -> {
                                            // Optional auto-finalize (D10)
                                            Mono<Void> maybeFinalize;
                                            if (project.isAutoFinalizeMilestoneInvoices()) {
                                                maybeFinalize = invoiceService
                                                        .setStatus(createdInvoice.getId(), Invoice.Status.SENT)
                                                        .then();
                                            } else {
                                                maybeFinalize = Mono.empty();
                                            }
                                            return maybeFinalize
                                                    .then(publishMilestoneCompleted(
                                                            tenantId, milestoneWithRef, createdInvoice.getId()))
                                                    .thenReturn(milestoneWithRef);
                                        });
                            });
                });
    }

    private Mono<Void> publishMilestoneCompleted(UUID tenantId, Milestone milestone, UUID spawnedInvoiceId) {
        if (milestone.getStatus() != MilestoneStatus.COMPLETED) {
            return Mono.empty();
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", milestone.getProjectId().toString());
        payload.put("milestoneId", milestone.getId().toString());
        payload.put("triggersInvoice", milestone.isTriggersInvoiceOnComplete());
        payload.put("spawnedInvoiceId", spawnedInvoiceId != null ? spawnedInvoiceId.toString() : null);
        events.publish(DomainEvent.of(DomainEventType.MILESTONE_COMPLETED, tenantId, milestone.getId(), payload));
        return Mono.empty();
    }
}
