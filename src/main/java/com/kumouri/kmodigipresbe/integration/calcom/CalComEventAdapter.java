package com.kumouri.kmodigipresbe.integration.calcom;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

/**
 * Isolates all Cal.com webhook payload-shape assumptions (F-D7 adapter-boundary
 * discipline, applied to Cal.com: every assumption about the Cal.com payload
 * lives <em>only</em> here — correcting against the real Cal.com payload spec is
 * a one-file change).
 *
 * <p>The WireMock stub in {@code CalComWebhookIT} is the single contract
 * coded-to.
 *
 * <h2>Assumed payload shape (H.2 ledger: assumption isolated here)</h2>
 * Cal.com v2 webhook payload structure (coded to the WireMock stub):
 * <pre>{@code
 * {
 *   "triggerEvent": "BOOKING_CREATED" | "BOOKING_RESCHEDULED" | "BOOKING_CANCELLED" | ...,
 *   "payload": {
 *     "uid": "<booking uid — the reconcile key>",
 *     "title": "<meeting title>",
 *     "startTime": "<ISO-8601 local datetime>",
 *     "endTime":   "<ISO-8601 local datetime>",
 *     "attendees": [{ "email": "<email>", "name": "<name>" }, ...],
 *     "organizer": { "email": "<email>", "name": "<name>" }
 *   }
 * }
 * }</pre>
 * The event id is taken from a top-level {@code id} or {@code eventUid} field
 * (both are tried; the WireMock stub uses {@code id}).
 */
public final class CalComEventAdapter {

    private CalComEventAdapter() {
    }

    /**
     * Parses the Cal.com webhook payload root into a structured {@link CalComEvent}.
     * Returns null-safe defaults; never throws.
     *
     * @param root the Jackson {@code JsonNode} of the webhook body
     * @return a populated (possibly partial) {@link CalComEvent}
     */
    public static CalComEvent parse(JsonNode root) {
        // Event id: try "id" first, then "eventUid"
        String eventId = root.path("id").asText(null);
        if (eventId == null || eventId.isBlank()) {
            eventId = root.path("eventUid").asText(null);
        }

        String triggerEvent = root.path("triggerEvent").asText(null);
        EventType type = mapTriggerEvent(triggerEvent);

        JsonNode payload = root.path("payload");

        String bookingUid  = payload.path("uid").asText(null);
        String title       = payload.path("title").asText(null);
        String startRaw    = payload.path("startTime").asText(null);
        String endRaw      = payload.path("endTime").asText(null);

        LocalDateTime start = parseDateTime(startRaw);
        LocalDateTime end   = parseDateTime(endRaw);

        // Attendee email (first attendee, for contact resolution)
        String attendeeEmail = null;
        JsonNode attendees = payload.path("attendees");
        if (attendees.isArray() && attendees.size() > 0) {
            attendeeEmail = attendees.get(0).path("email").asText(null);
        }

        String organizerEmail = payload.path("organizer").path("email").asText(null);

        return new CalComEvent(eventId, type, bookingUid, title, start, end,
                attendeeEmail, organizerEmail);
    }

    private static EventType mapTriggerEvent(String trigger) {
        if (trigger == null) return EventType.OTHER;
        return switch (trigger) {
            case "BOOKING_CREATED"      -> EventType.BOOKING_CREATED;
            case "BOOKING_RESCHEDULED"  -> EventType.BOOKING_RESCHEDULED;
            case "BOOKING_CANCELLED"    -> EventType.BOOKING_CANCELLED;
            default                     -> EventType.OTHER;
        };
    }

    private static LocalDateTime parseDateTime(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            // Try ISO_LOCAL_DATE_TIME first (e.g. "2025-06-01T10:00:00")
            return LocalDateTime.parse(raw, DateTimeFormatter.ISO_DATE_TIME);
        } catch (DateTimeParseException e1) {
            try {
                return LocalDateTime.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE_TIME);
            } catch (DateTimeParseException e2) {
                return null;
            }
        }
    }

    /**
     * A structured representation of a Cal.com webhook event.
     *
     * @param eventId        the event dedupe id (from {@code id} or {@code eventUid})
     * @param type           the mapped event type
     * @param bookingUid     the Cal.com booking uid — the Meeting projection key
     * @param title          the meeting/booking title (nullable)
     * @param start          the booking start time (nullable)
     * @param end            the booking end time (nullable)
     * @param attendeeEmail  the first attendee's email (nullable — for contact resolution)
     * @param organizerEmail the organizer's email (nullable)
     */
    public record CalComEvent(
            String eventId,
            EventType type,
            String bookingUid,
            String title,
            LocalDateTime start,
            LocalDateTime end,
            String attendeeEmail,
            String organizerEmail) {
    }

    /**
     * Cal.com trigger event types used for reconcile dispatch.
     */
    public enum EventType {
        BOOKING_CREATED,
        BOOKING_RESCHEDULED,
        BOOKING_CANCELLED,
        OTHER
    }
}
