package com.kumouri.kmodigipresbe.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public record BootstrapTenantRequest(
        @NotBlank @Pattern(regexp = "^[a-z0-9-]{2,40}$") String tenantSlug,
        @NotBlank String tenantDisplayName,
        @NotBlank @Email String adminEmail,
        @NotBlank String adminPassword,
        @NotBlank String adminDisplayName
) {
}
