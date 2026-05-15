package com.kumouri.kmodigipresbe.module.restaurantlight.widget;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

import java.time.Instant;

public record ReservationSubmissionDTO(
        @NotBlank @Email String email,
        String firstName,
        String lastName,
        String phone,
        Instant reservedAt,
        int partySize,
        String notes) {
}
