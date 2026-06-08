package com.kumouri.kmodigipresbe.module.chairfill.widget;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;
import java.util.UUID;

/**
 * Payload for an anonymous public waitlist-join submission
 * ({@code POST /public/widget/salon-waitlist/{token}}). The path token establishes tenant identity, so
 * no tenantId field is needed (the {@code SalonBookingSubmissionDTO} precedent).
 *
 * <p>{@code serviceMenuItemId} / {@code preferredStaffMemberId} / {@code earliestStart} /
 * {@code latestStart} are optional filters (null = match any). A client joining a waitlist to be texted
 * about openings is the SMS consent act — see {@link com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry}.
 */
public record WaitlistJoinSubmissionDTO(
        @NotBlank @Email String email,
        String firstName,
        String lastName,
        @NotBlank String phone,
        String serviceMenuItemId,
        UUID preferredStaffMemberId,
        Instant earliestStart,
        Instant latestStart,
        String notes) {
}
