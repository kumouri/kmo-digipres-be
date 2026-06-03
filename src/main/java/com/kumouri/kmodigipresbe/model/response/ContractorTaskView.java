package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.project.Task;

import java.time.LocalDate;

/**
 * Contractor-facing task projection (Phase J — J2).
 *
 * <p>Returned for {@code GET /me/contractor/projects/{id}/tasks} after the assigned-project
 * gate. Work-visible fields only; deliberately omits {@code tenantId}, {@code version}, and
 * {@code assigneeUserId} (who-is-on-what is staff/management info, not contractor-scoped).
 */
public record ContractorTaskView(
        String id,
        String projectId,
        String milestoneId,
        String title,
        String description,
        Task.TaskStatus status,
        Task.TaskPriority priority,
        LocalDate dueDate,
        int orderIndex) {

    public static ContractorTaskView from(Task task) {
        return new ContractorTaskView(
                task.getId() == null ? null : task.getId().toString(),
                task.getProjectId() == null ? null : task.getProjectId().toString(),
                task.getMilestoneId() == null ? null : task.getMilestoneId().toString(),
                task.getTitle(),
                task.getDescription(),
                task.getStatus(),
                task.getPriority(),
                task.getDueDate(),
                task.getOrderIndex());
    }
}
