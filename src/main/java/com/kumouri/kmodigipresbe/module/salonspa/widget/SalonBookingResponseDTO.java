package com.kumouri.kmodigipresbe.module.salonspa.widget;

import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Response from the public salon booking widget endpoint. When
 * {@code depositRequired=true}, the caller should direct the customer to
 * pay the deposit invoice (referenced by {@code depositInvoiceId}) before
 * the booking transitions from {@code PENDING_DEPOSIT} to {@code CONFIRMED}.
 */
public record SalonBookingResponseDTO(
        UUID contactId,
        UUID bookingId,
        BookingStatus status,
        boolean depositRequired,
        BigDecimal depositAmount,
        UUID depositInvoiceId) {
}
