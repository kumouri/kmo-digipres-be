package com.kumouri.kmodigipresbe.model.waitlist;

import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

/**
 * E4 — a freed slot to gap-fill, expressed in <strong>vertical-agnostic</strong> terms. This is the input
 * to {@link com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine#gapFill} and the value handed to a
 * {@link SlotMaterializer} when the first YES claims it.
 *
 * <h2>Why a plain value record (not a {@code @Document})</h2>
 * The engine never persists a slot — a slot is whatever a consumer's freed domain record (a cancelled
 * salon Booking, a cancelled health Appointment, a no-show field-service WorkOrder window, …) projects
 * into at gap-fill time. <strong>There is NO Booking / Appointment coupling here</strong> (design
 * directive #2): the engine only ever sees {@code slotType} + {@code providerId} + the window + the
 * opaque {@code slotKey}. The consumer's {@link SlotMaterializer} owns the real domain record creation on
 * claim, so the contended resource the engine arbitrates is just "this slot," identified by {@code slotKey}.
 *
 * @param slotType        the consumer's slot category (e.g. {@code "salon-booking"}, {@code "health-appt"});
 *                        the {@link SlotMaterializer} dispatch key + the {@link WaitlistEntry#getSlotType()}
 *                        match key. Required.
 * @param slotKey         the opaque id of the contended resource (the consumer's freed-record id as a
 *                        String) — used in the per-slot claim doc {@code _id} so two YESs for the SAME slot
 *                        resolve to exactly one winner. Required.
 * @param providerId      the optional resource/provider the slot is with (stylist / clinician / tech);
 *                        null = any provider (a flexible waitlister matches).
 * @param slotStart       the freed window start (Instant). Required.
 * @param slotEnd         the freed window end (Instant). Nullable (derivable from {@code durationMinutes}).
 * @param durationMinutes the slot length in minutes (carried to the materializer for the new record).
 */
@Builder(toBuilder = true)
public record WaitlistSlot(
        String slotType,
        String slotKey,
        UUID providerId,
        Instant slotStart,
        Instant slotEnd,
        int durationMinutes) {
}
