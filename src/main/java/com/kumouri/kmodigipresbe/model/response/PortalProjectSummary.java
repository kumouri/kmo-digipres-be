package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.project.Project;

import java.time.LocalDate;

/**
 * Portal-facing project projection (Phase G — G-D1).
 *
 * <p>Omits {@code tenantId}, {@code version}, {@code dealId}, {@code ownerId},
 * {@code autoFinalizeMilestoneInvoices}, {@code customFields}, and timestamps —
 * these are either staff-internal or already implied by the portal context.
 * Client-visible fields only.
 */
public record PortalProjectSummary(
        String id,
        String code,
        String name,
        Project.ProjectStatus status,
        String description,
        LocalDate startDate,
        LocalDate targetEndDate,
        LocalDate actualEndDate) {

    public static PortalProjectSummary from(Project project) {
        return new PortalProjectSummary(
                project.getId() == null ? null : project.getId().toString(),
                project.getCode(),
                project.getName(),
                project.getStatus(),
                project.getDescription(),
                project.getStartDate(),
                project.getTargetEndDate(),
                project.getActualEndDate());
    }
}
