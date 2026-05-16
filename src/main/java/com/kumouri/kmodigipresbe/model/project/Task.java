package com.kumouri.kmodigipresbe.model.project;

import com.kumouri.kmodigipresbe.audit.Auditable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An individual unit of work within a {@link Project}, optionally attached to a
 * {@link Milestone}. {@code status} drives the Task kanban board (FE Phase C C.10).
 * Each status value corresponds to a kanban column (C-D11).
 */
@Document("tasks")
@CompoundIndex(name = "tenant_project_idx",  def = "{'tenantId':1,'projectId':1}")
@CompoundIndex(name = "tenant_status_idx",   def = "{'tenantId':1,'status':1}")
@CompoundIndex(name = "tenant_assignee_idx", def = "{'tenantId':1,'assigneeUserId':1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Task implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Required FK to the parent Project. */
    private UUID projectId;

    /** Nullable — a task may or may not roll up to a milestone. */
    private UUID milestoneId;

    /** Required — service validates (errorCode 3421 if blank). */
    private String title;

    private String description;

    /** Kanban column: TODO / IN_PROGRESS / BLOCKED / DONE. */
    @Builder.Default
    private TaskStatus status = TaskStatus.TODO;

    @Builder.Default
    private TaskPriority priority = TaskPriority.MEDIUM;

    private UUID assigneeUserId;

    private LocalDate dueDate;

    /** Ordering within a kanban column. */
    @Builder.Default
    private int orderIndex = 0;

    /** Set on transition to DONE. */
    private Instant completedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum TaskStatus {
        TODO, IN_PROGRESS, BLOCKED, DONE
    }

    public enum TaskPriority {
        LOW, MEDIUM, HIGH, URGENT
    }
}
