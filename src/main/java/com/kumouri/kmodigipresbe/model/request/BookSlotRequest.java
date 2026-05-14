package com.kumouri.kmodigipresbe.model.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.time.Instant;

public record BookSlotRequest(
        @NotNull Instant slotStart,
        @NotBlank @Email String attendeeEmail,
        @NotBlank String attendeeName,
        String notes) {
}
