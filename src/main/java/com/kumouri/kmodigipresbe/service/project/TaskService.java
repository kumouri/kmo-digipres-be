package com.kumouri.kmodigipresbe.service.project;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.project.Task;
import com.kumouri.kmodigipresbe.model.project.Task.TaskStatus;
import com.kumouri.kmodigipresbe.repository.project.TaskRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the {@link Task} lifecycle including kanban status moves (C-D2, C-D11).
 *
 * <p>No invoice spawning here — tasks do not trigger invoices. Domain events are
 * advisory (TASK_CREATED, TASK_STATUS_CHANGED) for workflow rules / webhooks.
 */
@Service
@RequiredArgsConstructor
public class TaskService {

    private final TaskRepository tasks;
    private final DomainEventPublisher events;

    public Flux<Task> findByProject(UUID projectId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> tasks.findAllByTenantIdAndProjectIdOrderByOrderIndexAsc(
                        ctx.tenantId(), projectId));
    }

    public Mono<Task> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> tasks.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException("Task not found", 3420, 404)));
    }

    public Mono<Task> create(UUID projectId, Task body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getTitle() == null || body.getTitle().isBlank()) {
                return Mono.error(new DigiPresBeException("Task title is required", 3421, 400));
            }
            body.setId(null);
            body.setTenantId(ctx.tenantId());
            body.setProjectId(projectId);
            if (body.getStatus() == null) {
                body.setStatus(TaskStatus.TODO);
            }
            if (body.getPriority() == null) {
                body.setPriority(Task.TaskPriority.MEDIUM);
            }
            return tasks.save(body).flatMap(saved -> {
                events.publish(DomainEvent.of(
                        DomainEventType.TASK_CREATED,
                        saved.getTenantId(), saved.getId(),
                        Map.of("projectId", projectId.toString(),
                                "title", saved.getTitle(),
                                "status", saved.getStatus().name())));
                return Mono.just(saved);
            });
        });
    }

    public Mono<Task> update(UUID id, Task patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getTitle() != null) {
                if (patch.getTitle().isBlank()) {
                    return Mono.error(new DigiPresBeException("Task title is required", 3421, 400));
                }
                existing.setTitle(patch.getTitle());
            }
            if (patch.getDescription() != null) existing.setDescription(patch.getDescription());
            if (patch.getPriority() != null) existing.setPriority(patch.getPriority());
            if (patch.getAssigneeUserId() != null) existing.setAssigneeUserId(patch.getAssigneeUserId());
            if (patch.getDueDate() != null) existing.setDueDate(patch.getDueDate());
            if (patch.getMilestoneId() != null) existing.setMilestoneId(patch.getMilestoneId());
            existing.setOrderIndex(patch.getOrderIndex());
            return tasks.save(existing);
        });
    }

    /**
     * Kanban status move (C-D11). Publishes TASK_STATUS_CHANGED; sets completedAt on DONE.
     */
    public Mono<Task> changeStatus(UUID id, TaskStatus newStatus) {
        return findById(id).flatMap(existing -> {
            TaskStatus prev = existing.getStatus();
            if (prev == newStatus) {
                return Mono.just(existing);
            }
            existing.setStatus(newStatus);
            if (newStatus == TaskStatus.DONE && existing.getCompletedAt() == null) {
                existing.setCompletedAt(Instant.now());
            } else if (newStatus != TaskStatus.DONE) {
                existing.setCompletedAt(null);
            }
            return tasks.save(existing).flatMap(saved -> {
                events.publish(DomainEvent.of(
                        DomainEventType.TASK_STATUS_CHANGED,
                        saved.getTenantId(), saved.getId(),
                        Map.of("from", prev.name(), "to", newStatus.name())));
                return Mono.just(saved);
            });
        });
    }

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(t -> tasks.deleteById(t.getId()));
    }
}
