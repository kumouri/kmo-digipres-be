package com.kumouri.kmodigipresbe.service.project;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.project.Project.ProjectStatus;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the {@link Project} lifecycle (C-D2, C-D3, C-D4, C-D6).
 *
 * <p>The Deal→Project conversion ({@link #convertFromDeal}) uses an explicit
 * {@code existsByTenantIdAndDealId} boolean check for idempotency — never
 * {@code switchIfEmpty(create)}, which is the documented reactive-empty-completion
 * trap (§9 item 2 of the Phase C plan).
 */
@Service
@RequiredArgsConstructor
public class ProjectService {

    private static final Set<String> ILLEGAL_STATUS_TRANSITIONS = Set.of(
            "CANCELLED->PLANNING",
            "CANCELLED->ACTIVE",
            "CANCELLED->ON_HOLD",
            "CANCELLED->COMPLETED",
            "COMPLETED->PLANNING",
            "COMPLETED->ACTIVE",
            "COMPLETED->ON_HOLD"
    );

    private final ProjectRepository projects;
    private final DealRepository deals;
    private final ProjectCodeGenerator codeGenerator;
    private final DomainEventPublisher events;

    public Flux<Project> findAll() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> projects.findAllByTenantId(ctx.tenantId()));
    }

    public Mono<Project> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> projects.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Project not found", 3400, 404)));
    }

    public Mono<Project> create(Project body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getName() == null || body.getName().isBlank()) {
                return Mono.error(new DigiPresBeException("Project name is required", 3401, 400));
            }
            body.setId(null);
            body.setTenantId(ctx.tenantId());
            if (body.getStatus() == null) {
                body.setStatus(ProjectStatus.PLANNING);
            }
            return generateCodeAndSave(body).flatMap(saved -> {
                events.publish(DomainEvent.of(
                        DomainEventType.PROJECT_CREATED,
                        saved.getTenantId(), saved.getId(),
                        Map.of("code", saved.getCode(), "name", saved.getName())));
                return Mono.just(saved);
            });
        });
    }

    public Mono<Project> update(UUID id, Project patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getName() != null) {
                if (patch.getName().isBlank()) {
                    return Mono.error(new DigiPresBeException("Project name is required", 3401, 400));
                }
                existing.setName(patch.getName());
            }
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getPrimaryContactId() != null) existing.setPrimaryContactId(patch.getPrimaryContactId());
            if (patch.getCompanyId() != null) existing.setCompanyId(patch.getCompanyId());
            if (patch.getOwnerId() != null) existing.setOwnerId(patch.getOwnerId());
            if (patch.getStartDate() != null) existing.setStartDate(patch.getStartDate());
            if (patch.getTargetEndDate() != null) existing.setTargetEndDate(patch.getTargetEndDate());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            existing.setAutoFinalizeMilestoneInvoices(patch.isAutoFinalizeMilestoneInvoices());
            return projects.save(existing);
        });
    }

    public Mono<Project> setStatus(UUID id, ProjectStatus target) {
        return findById(id).flatMap(existing -> {
            String transitionKey = existing.getStatus().name() + "->" + target.name();
            if (ILLEGAL_STATUS_TRANSITIONS.contains(transitionKey)) {
                return Mono.error(new DigiPresBeException(
                        "Transition " + transitionKey + " is not permitted", 3402, 409));
            }
            ProjectStatus prev = existing.getStatus();
            existing.setStatus(target);
            if (target == ProjectStatus.COMPLETED && existing.getActualEndDate() == null) {
                existing.setActualEndDate(LocalDate.now());
            }
            return projects.save(existing).flatMap(saved -> {
                events.publish(DomainEvent.of(
                        DomainEventType.PROJECT_STATUS_CHANGED,
                        saved.getTenantId(), saved.getId(),
                        Map.of("from", prev.name(), "to", target.name())));
                return Mono.just(saved);
            });
        });
    }

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(p -> projects.deleteById(p.getId()));
    }

    /**
     * Converts a WON Deal to a Project (C-D6). Idempotent via the explicit boolean
     * guard (C-D4): a second call for the same {@code dealId} returns the existing
     * Project (200) instead of creating a duplicate.
     *
     * <p><strong>Does NOT use switchIfEmpty for the idempotency check.</strong>
     * The pattern is:
     * <pre>
     *   existsByTenantIdAndDealId(...).flatMap(exists -&gt;
     *     exists ? findFirstByTenantIdAndDealId(...) : doCreateFromDeal(...))
     * </pre>
     * This avoids the reactive-empty-completion trap where
     * {@code findBy(...).flatMap(...).switchIfEmpty(create)} would double-create
     * when the upstream completes empty.
     *
     * @return {@link ConversionResult} carrying the Project plus a {@code created} flag
     *         so the controller can return 201 on first call and 200 on repeats.
     */
    public Mono<ConversionResult> convertFromDeal(UUID dealId) {
        return TenantContextHolder.required().flatMap(ctx ->
                deals.findByTenantIdAndId(ctx.tenantId(), dealId)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Deal not found", 3431, 404)))
                        .flatMap(deal -> {
                            if (deal.getStage() != PipelineStage.WON) {
                                return Mono.error(new DigiPresBeException(
                                        "Deal must be in WON stage to convert to a Project", 3432, 409));
                            }
                            // Explicit boolean idempotency — NOT switchIfEmpty(create) (C-D4)
                            return projects.existsByTenantIdAndDealId(ctx.tenantId(), dealId)
                                    .flatMap(exists -> {
                                        if (exists) {
                                            // Idempotent: return the existing project
                                            return projects.findFirstByTenantIdAndDealId(ctx.tenantId(), dealId)
                                                    .map(p -> new ConversionResult(p, false));
                                        } else {
                                            return doCreateFromDeal(ctx, deal)
                                                    .map(p -> new ConversionResult(p, true));
                                        }
                                    });
                        }));
    }

    private Mono<Project> doCreateFromDeal(TenantContext ctx, Deal deal) {
        String name = deal.getTitle() != null ? deal.getTitle() : "Project from Deal";
        Project toCreate = Project.builder()
                .tenantId(ctx.tenantId())
                .name(name)
                .status(ProjectStatus.PLANNING)
                .dealId(deal.getId())
                .primaryContactId(deal.getPrimaryContactId())
                .companyId(deal.getCompanyId())
                .ownerId(deal.getOwnerId())
                .build();
        return generateCodeAndSave(toCreate).flatMap(saved -> {
            events.publish(DomainEvent.of(
                    DomainEventType.PROJECT_CREATED,
                    saved.getTenantId(), saved.getId(),
                    Map.of("code", saved.getCode(), "dealId", deal.getId().toString())));
            return Mono.just(saved);
        });
    }

    /**
     * Generates a code and saves the project. Does NOT publish domain events —
     * callers ({@link #create} and {@link #doCreateFromDeal}) publish the
     * {@code PROJECT_CREATED} event after this returns.
     */
    private Mono<Project> generateCodeAndSave(Project project) {
        return codeGenerator.next(project.getTenantId())
                .flatMap(code -> {
                    project.setCode(code);
                    return projects.save(project)
                            .onErrorResume(DuplicateKeyException.class, ex ->
                                    // Defensive retry on unique-index collision (C-D3)
                                    codeGenerator.next(project.getTenantId())
                                            .flatMap(retryCode -> {
                                                project.setCode(retryCode);
                                                return projects.save(project);
                                            })
                                            .onErrorMap(DuplicateKeyException.class, e ->
                                                    new DigiPresBeException(
                                                            "Project code generation failed after retry", 3433, 500)));
                });
    }

    /**
     * Carries both the Project and whether it was freshly created (vs. existing).
     * The controller uses this to return 201 on first call and 200 on repeats (C-D4).
     */
    public record ConversionResult(Project project, boolean created) {}
}
