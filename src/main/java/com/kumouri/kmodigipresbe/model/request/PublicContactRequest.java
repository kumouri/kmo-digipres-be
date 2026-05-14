package com.kumouri.kmodigipresbe.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Minimal request body for the unauthenticated public-contact endpoint
 * ({@code POST /public/{tenantSlug}/contacts}). Deliberately a smaller, more
 * defensive subset of {@link ContactDTO}: no {@code tenantId}, no {@code ownerId},
 * no {@code customFields}, no {@code companyId}. The path's tenant slug is the
 * sole tenant signal — see {@code PublicContactController}.
 *
 * <p>At least one of {@code firstName}/{@code lastName} must be present; that
 * cross-field check is enforced in the controller rather than via a custom
 * bean-validation annotation to keep this DTO trivial.
 */
public record PublicContactRequest(
        @NotBlank @Email String email,
        String firstName,
        String lastName,
        String phone,
        String message) {
}
