package com.kumouri.kmodigipresbe.module.frontdesk.controller.dto;

import com.kumouri.kmodigipresbe.model.activity.Activity;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * FrontDesk IQ (FD-5a) — one after-hours voicemail callback on the staff-facing callback inbox
 * ({@code GET /frontdesk/callbacks}). A lean, <strong>logistics-only</strong> projection of an FD-3
 * health front-desk callback {@link Activity} ({@code CALL, INBOUND}): the fields a front desk needs to
 * return the call — caller name, callback number, the routing bucket, and when it came in.
 *
 * <h2>The marquee fence — F2 (NEVER a transcript)</h2>
 * <p>The FD-3 health front-desk voicemail strategy ({@code persistTranscript()=false}) deliberately
 * never stores the raw transcript: {@code TwilioVoicemailService.logCallActivity} writes the fixed
 * redaction marker as the {@code Activity.body} and omits the recording pointer, so a patient saying "I
 * need my insulin refilled" never lands in a stored, queryable record. <strong>This DTO carries that
 * fence forward into the read:</strong> it projects ONLY the logistics fields off the Activity's
 * {@code payload.extractedJson} ({@code name / callbackNumber / intentBucket / callbackRequested}) plus
 * the caller-ID and timestamp — it deliberately has <strong>no {@code body} / {@code transcript} /
 * {@code recordingUrl} field</strong>, so the callback inbox read can never surface the spoken words
 * (verified by the {@code FrontDeskBoardReadIT} F2 assertion). PHI never leaves the data layer.
 *
 * @param activityId        the callback Activity id
 * @param contactId         the caller's Contact (the Activity {@code subjectId}); resolve board-side
 * @param callerName        the caller's name as extracted (logistics only), or null
 * @param callbackPhone     the number to call back — the extracted {@code callbackNumber} if present,
 *                          else the Twilio caller-ID {@code From} (so a callback is never undialable)
 * @param intentBucket      the logistics routing bucket (SCHEDULING / BILLING /
 *                          PRESCRIPTION_REFILL_REQUEST / GENERAL_CALLBACK / OTHER) — never a diagnosis
 * @param callbackRequested whether the caller asked to be called back
 * @param receivedAt        when the callback was logged (the Activity {@code occurredAt}; newest-first)
 */
public record CallbackInboxItemDTO(
        UUID activityId,
        UUID contactId,
        String callerName,
        String callbackPhone,
        String intentBucket,
        boolean callbackRequested,
        Instant receivedAt) {

    /**
     * Project a health front-desk callback {@link Activity} to a logistics-only inbox row. Reads the
     * logistics fields off {@code payload.extractedJson} (the health strategy's own map — name /
     * callbackNumber / intentBucket / callbackRequested) and the caller-ID off {@code payload.fromNumber};
     * <strong>never reads {@code body}</strong> (the F2 redaction marker) or any recording pointer. The
     * callback number prefers the extracted {@code callbackNumber}, falling back to the caller-ID so the
     * row is always dialable.
     */
    @SuppressWarnings("unchecked")
    public static CallbackInboxItemDTO from(Activity a) {
        Map<String, Object> payload = a.getPayload() == null ? Map.of() : a.getPayload();
        Object extractedRaw = payload.get("extractedJson");
        Map<String, Object> extracted = (extractedRaw instanceof Map)
                ? (Map<String, Object>) extractedRaw
                : Map.of();

        String name = asString(extracted.get("name"));
        String extractedCallback = asString(extracted.get("callbackNumber"));
        String fromNumber = asString(payload.get("fromNumber"));
        String callbackPhone = extractedCallback != null ? extractedCallback : fromNumber;
        String intentBucket = asString(extracted.get("intentBucket"));
        boolean callbackRequested = Boolean.TRUE.equals(extracted.get("callbackRequested"));

        return new CallbackInboxItemDTO(
                a.getId(),
                a.getSubjectId(),
                name,
                callbackPhone,
                intentBucket,
                callbackRequested,
                a.getOccurredAt());
    }

    private static String asString(Object v) {
        if (v == null) {
            return null;
        }
        String s = v.toString();
        return s.isBlank() ? null : s;
    }
}
