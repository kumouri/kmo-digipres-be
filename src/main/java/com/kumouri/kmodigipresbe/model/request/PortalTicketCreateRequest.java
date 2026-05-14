package com.kumouri.kmodigipresbe.model.request;

import jakarta.validation.constraints.NotBlank;

/**
 * Strict portal ticket create payload. Records ignore unknown JSON fields by default,
 * so smuggled {@code tenantId}, {@code subjectId}, {@code ownerId}, {@code type},
 * {@code direction}, or {@code payload} keys are silently dropped at the Jackson
 * boundary — the controller derives all of those from the caller's TenantContext +
 * linked Contact.
 */
public record PortalTicketCreateRequest(
        @NotBlank String summary,
        String body) {
}
