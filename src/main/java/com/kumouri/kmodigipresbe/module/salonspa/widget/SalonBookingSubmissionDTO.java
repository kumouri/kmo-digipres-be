package com.kumouri.kmodigipresbe.module.salonspa.widget;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

/**
 * Payload for anonymous public salon booking widget submissions
 * ({@code POST /public/widget/salon-booking/{token}}). The path token
 * establishes tenant identity so no tenantId field is needed here.
 */
public record SalonBookingSubmissionDTO(
        @NotBlank @Email String email,
        String firstName,
        String lastName,
        String phone,
        String serviceMenuItemId,
        Instant preferredStart,
        String notes) {
}
