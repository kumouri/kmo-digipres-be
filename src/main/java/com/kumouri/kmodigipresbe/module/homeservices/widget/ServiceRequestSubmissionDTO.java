package com.kumouri.kmodigipresbe.module.homeservices.widget;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/**
 * Phase 10e — public service-request widget submission payload. Posted by an
 * embedded widget on a tenant's website; the path-bound token established the
 * tenant identity, so this DTO carries no {@code tenantId}.
 *
 * <p>{@code email} is the only hard-required field (it keys contact upsert).
 * {@code firstName}/{@code lastName} are recommended but tolerated empty —
 * many "request a quote" forms only ask for email + phone. {@code jobSiteId}
 * is optional: tenants who pre-issue tokens scoped to a specific property can
 * populate it; otherwise the resulting WorkOrder gets a null JobSite that
 * staff assign during triage.
 */
public record ServiceRequestSubmissionDTO(
        @NotBlank @Email String email,
        String firstName,
        String lastName,
        String phone,
        UUID jobSiteId,
        String serviceType,
        String notes) {
}
