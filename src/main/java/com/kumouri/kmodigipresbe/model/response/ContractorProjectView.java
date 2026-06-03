package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.project.Project;

import java.time.LocalDate;

/**
 * Contractor-facing project projection (Phase J — J2), the contractor analogue of
 * {@link PortalProjectSummary}.
 *
 * <p>Client-/work-visible fields only. Deliberately omits {@code tenantId}, {@code version},
 * {@code dealId}, {@code ownerId}, {@code primaryContactId}, {@code companyId},
 * {@code autoFinalizeMilestoneInvoices}, {@code customFields}, and timestamps — these are
 * sales-internal or staff-internal and must not leak to a scoped contractor. The linked
 * client is exposed separately and read-only via {@link ContractorClientView}.
 */
public record ContractorProjectView(
        String id,
        String code,
        String name,
        Project.ProjectStatus status,
        String description,
        LocalDate startDate,
        LocalDate targetEndDate,
        LocalDate actualEndDate) {

    public static ContractorProjectView from(Project project) {
        return new ContractorProjectView(
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
