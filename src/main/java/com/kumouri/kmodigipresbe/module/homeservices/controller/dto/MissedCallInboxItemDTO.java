package com.kumouri.kmodigipresbe.module.homeservices.controller.dto;

import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One card in the Home Services "Missed-Call Inbox" (HS-1 — the read contract HS-4's admin UI
 * consumes). A lean projection of a voicemail-sourced DRAFT {@link WorkOrder}: the triage fields a
 * dispatcher needs to decide Schedule vs Dismiss at a glance, without leaking the full WorkOrder
 * document shape.
 *
 * <p>{@code urgency}/{@code jobValueBand}/{@code trade}/{@code callSid}/{@code source} are read off
 * the WorkOrder's {@code customFields} the {@code MultiTradeExtractionStrategy} stamped;
 * {@code serviceType} is the trade; {@code notes} carries the symptom + raw transcript.
 *
 * @param id              the WorkOrder id
 * @param workOrderNumber the server-assigned {@code YYYY-MM-NNNN} number
 * @param trade           the trade discipline (= {@code serviceType})
 * @param urgency         the routing urgency stamped at intake ({@code EMERGENCY|URGENT|ROUTINE|
 *                        UNTRIAGED})
 * @param jobValueBand    the coarse $-band hint, if any (nullable)
 * @param title           the WorkOrder title ({@code "<TRADE> — <URGENCY>"})
 * @param notes           the symptom + raw transcript
 * @param callSid         the originating Twilio CallSid (nullable on legacy/non-voicemail DRAFTs)
 * @param createdAt       when the DRAFT was created (newest-first ordering key)
 */
public record MissedCallInboxItemDTO(
        UUID id,
        String workOrderNumber,
        String trade,
        String urgency,
        String jobValueBand,
        String title,
        String notes,
        String callSid,
        Instant createdAt) {

    public static MissedCallInboxItemDTO from(WorkOrder wo) {
        Map<String, Object> cf = wo.getCustomFields() == null ? Map.of() : wo.getCustomFields();
        return new MissedCallInboxItemDTO(
                wo.getId(),
                wo.getWorkOrderNumber(),
                wo.getServiceType(),
                asString(cf.get("urgency")),
                asString(cf.get("jobValueBand")),
                wo.getTitle(),
                wo.getNotes(),
                asString(cf.get("callSid")),
                wo.getCreatedAt());
    }

    private static String asString(Object v) {
        return v == null ? null : v.toString();
    }
}
