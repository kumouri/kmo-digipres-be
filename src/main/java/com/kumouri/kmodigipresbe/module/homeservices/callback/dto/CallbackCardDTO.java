package com.kumouri.kmodigipresbe.module.homeservices.callback.dto;

import com.kumouri.kmodigipresbe.module.homeservices.callback.CallbackRequest;

import java.time.Instant;
import java.util.UUID;

/**
 * T5 (Home Services "Instant Callback") — one card in the dispatcher's <strong>revenue-ranked</strong>
 * callback queue. A lean projection of a {@link CallbackRequest} (the {@code MissedCallInboxItemDTO}
 * precedent): the triage fields a dispatcher needs to pick the next call at a glance — the AI one-liner,
 * the requested window, the urgency + $-band, and the {@code revenueScore} the queue is sorted by — without
 * leaking the full document shape.
 *
 * @param id                  the CallbackRequest id
 * @param contactId           the caller's Contact
 * @param fromPhone           the caller's phone (E.164)
 * @param mode                IMMEDIATE | SCHEDULED
 * @param requestedWindowText the caller's raw window phrase (nullable)
 * @param requestedAt         the best-effort parsed window instant (nullable)
 * @param status              REQUESTED | DISPATCHED | COMPLETED | CANCELLED
 * @param summaryLine         the AI one-line summary from the voicemail (nullable)
 * @param urgency             the routing urgency (nullable)
 * @param jobValueBand        the coarse $-band (nullable)
 * @param revenueScore        the deterministic rank score the queue is ordered by (higher = first)
 * @param workOrderId         the DRAFT WorkOrder the voicemail intake created (nullable)
 * @param callSid             the originating voicemail CallSid (nullable)
 * @param createdAt           when the callback was requested
 */
public record CallbackCardDTO(
        UUID id,
        UUID contactId,
        String fromPhone,
        String mode,
        String requestedWindowText,
        Instant requestedAt,
        String status,
        String summaryLine,
        String urgency,
        String jobValueBand,
        long revenueScore,
        UUID workOrderId,
        String callSid,
        Instant createdAt) {

    public static CallbackCardDTO from(CallbackRequest r) {
        return new CallbackCardDTO(
                r.getId(),
                r.getContactId(),
                r.getFromPhone(),
                r.getMode() == null ? null : r.getMode().name(),
                r.getRequestedWindowText(),
                r.getRequestedAt(),
                r.getStatus() == null ? null : r.getStatus().name(),
                r.getSummaryLine(),
                r.getUrgency(),
                r.getJobValueBand(),
                r.getRevenueScore(),
                r.getWorkOrderId(),
                r.getCallSid(),
                r.getCreatedAt());
    }
}
