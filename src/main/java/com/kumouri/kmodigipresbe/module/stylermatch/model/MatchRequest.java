package com.kumouri.kmodigipresbe.module.stylermatch.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — the inputs a stylist match is scored from: a new client's requested
 * service + the style they want + (optionally) a preferred stylist, the slot they want, and who they
 * are. The stylist-side twin of the T9 {@code StyleConsultService.ManualInput}: every field is
 * nullable/blank-tolerant — a request with only a {@code styleCategory} still produces a ranked board
 * (a deterministic match is triage; the salon confirms).
 *
 * <p>Consumed by {@code StylerMatchScoringService.rank} (pure) and persisted (echoed) onto the
 * {@link StylerMatch} record by {@code StylerMatchService}.
 *
 * @param serviceMenuItemId  the stable id of the requested {@code ServiceMenuItem} (the hard-eligibility
 *                           signal + what the accept path books); nullable
 * @param styleCategory      the look the client wants (e.g. "balayage", "blonde highlights"); the primary
 *                           specialty-fit signal; nullable
 * @param length             requested/own hair length ("short"/"medium"/"long"); nullable
 * @param texture            requested/own hair texture ("straight"/"wavy"/"curly"/"coily"); nullable
 * @param color              requested/own color ("brunette"/"blonde"/"balayage"); nullable
 * @param preferredStaffMemberId an explicit stylist preference (the largest preference bump); nullable
 * @param contactId          the client (drives the past-preference signal from prior COMPLETED bookings);
 *                           nullable
 * @param slotStart          the start of the slot the client wants (the availability signal); nullable
 * @param slotEnd            the end of the requested slot; nullable
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MatchRequest {

    private String serviceMenuItemId;
    private String styleCategory;
    private String length;
    private String texture;
    private String color;
    private UUID preferredStaffMemberId;
    private UUID contactId;
    private Instant slotStart;
    private Instant slotEnd;

    /** True iff there is nothing usable to match on — no service, no style signal, no slot, no preference. */
    public boolean isEmpty() {
        return blank(serviceMenuItemId) && blank(styleCategory) && blank(length) && blank(texture)
                && blank(color) && preferredStaffMemberId == null && slotStart == null;
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
