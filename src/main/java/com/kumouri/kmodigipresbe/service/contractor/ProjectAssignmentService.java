package com.kumouri.kmodigipresbe.service.contractor;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contractor.ProjectAssignment;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.contractor.ProjectAssignmentRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Manages {@link ProjectAssignment}s — who is on a project and at what bill/cost rate
 * (Phase J). ADMIN-gated at the controller.
 *
 * <p>{@link #assign} is idempotent on {@code (project, user)} via the explicit-boolean
 * pattern (never {@code switchIfEmpty(create)}, §9): a repeat returns the existing row
 * (200), a soft-deleted row is reactivated, a fresh pair is created (201). The unique
 * {@code tenant_project_user_idx} backstops a concurrent create.
 */
@Service
@RequiredArgsConstructor
public class ProjectAssignmentService {

    private final ProjectAssignmentRepository assignments;
    private final ProjectRepository projects;
    private final UserRepository users;
    private final DomainEventPublisher events;

    public Flux<ProjectAssignment> listByProject(UUID projectId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> assignments.findAllByTenantIdAndProjectId(ctx.tenantId(), projectId));
    }

    public Mono<AssignmentResult> assign(UUID projectId, ProjectAssignment body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID userId = body.getUserId();
            if (userId == null) {
                return Mono.error(new DigiPresBeException("userId is required", 4102, 400));
            }
            return projects.findByTenantIdAndId(ctx.tenantId(), projectId)
                    .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Project not found", 4101, 404)))
                    .flatMap(project -> users.findById(userId)
                            .filter(u -> ctx.tenantId().equals(u.getTenantId()))
                            .switchIfEmpty(Mono.error(() -> new DigiPresBeException("User not found", 4102, 404)))
                            .flatMap(user -> assignments
                                    .findFirstByTenantIdAndProjectIdAndUserId(ctx.tenantId(), projectId, userId)
                                    .map(Optional::of).defaultIfEmpty(Optional.empty())
                                    .flatMap(opt -> opt.isPresent()
                                            ? reactivateIfNeeded(opt.get(), body)
                                            : doCreate(ctx.tenantId(), projectId, userId, body))));
        });
    }

    private Mono<AssignmentResult> reactivateIfNeeded(ProjectAssignment existing, ProjectAssignment body) {
        if (existing.isActive()) {
            return Mono.just(new AssignmentResult(existing, false));
        }
        existing.setActive(true);
        if (body.getBillRateOverride() != null) existing.setBillRateOverride(body.getBillRateOverride());
        if (body.getCostRateOverride() != null) existing.setCostRateOverride(body.getCostRateOverride());
        if (body.getRole() != null) existing.setRole(body.getRole());
        return assignments.save(existing).map(saved -> new AssignmentResult(saved, false));
    }

    private Mono<AssignmentResult> doCreate(UUID tenantId, UUID projectId, UUID userId, ProjectAssignment body) {
        ProjectAssignment toCreate = ProjectAssignment.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .userId(userId)
                .billRateOverride(body.getBillRateOverride())
                .costRateOverride(body.getCostRateOverride())
                .role(body.getRole())
                .active(true)
                .build();
        return assignments.save(toCreate)
                .onErrorResume(DuplicateKeyException.class, ex ->
                        assignments.findFirstByTenantIdAndProjectIdAndUserId(tenantId, projectId, userId))
                .flatMap(saved -> {
                    events.publish(DomainEvent.of(DomainEventType.PROJECT_ASSIGNED, tenantId, saved.getId(),
                            Map.of("projectId", projectId.toString(), "userId", userId.toString())));
                    return Mono.just(new AssignmentResult(saved, true));
                });
    }

    public Mono<ProjectAssignment> updateAssignment(UUID projectId, UUID id, ProjectAssignment patch) {
        return load(projectId, id).flatMap(existing -> {
            if (patch.getBillRateOverride() != null) existing.setBillRateOverride(patch.getBillRateOverride());
            if (patch.getCostRateOverride() != null) existing.setCostRateOverride(patch.getCostRateOverride());
            if (patch.getRole() != null) existing.setRole(patch.getRole());
            return assignments.save(existing);
        });
    }

    public Mono<Void> unassign(UUID projectId, UUID id) {
        return load(projectId, id).flatMap(existing -> {
            existing.setActive(false);
            return assignments.save(existing)
                    .doOnNext(saved -> events.publish(DomainEvent.of(
                            DomainEventType.PROJECT_UNASSIGNED, saved.getTenantId(), saved.getId(),
                            Map.of("projectId", projectId.toString(), "userId", saved.getUserId().toString()))))
                    .then();
        });
    }

    private Mono<ProjectAssignment> load(UUID projectId, UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> assignments.findByTenantIdAndId(ctx.tenantId(), id))
                .filter(a -> projectId.equals(a.getProjectId()))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Assignment not found", 4106, 404)));
    }

    /** Carries the assignment plus whether it was freshly created (controller → 201 vs 200). */
    public record AssignmentResult(ProjectAssignment assignment, boolean created) {}
}
