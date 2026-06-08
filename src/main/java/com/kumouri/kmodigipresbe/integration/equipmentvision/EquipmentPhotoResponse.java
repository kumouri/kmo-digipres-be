package com.kumouri.kmodigipresbe.integration.equipmentvision;

import java.util.UUID;

/**
 * The public-facing result of an equipment-photo upload (HS-2 — Home Services "Front Desk That Never
 * Sleeps"), returned to the caller's browser/app after they follow the tokenized upload link from
 * the HS-1 auto-ack SMS.
 *
 * <p>Framed as <strong>triage, not truth</strong> (plan §8): the read enriches the DRAFT WorkOrder a
 * dispatcher confirms; the photo is always stored even when nothing legible is found. {@code enriched}
 * reflects whether the nameplate read produced at least one field that was written onto the WorkOrder
 * — a blank read (or a best-effort vision failure) still returns 200 with {@code enriched=false} and
 * a friendly "thanks, we've got it" message.
 *
 * @param enriched     whether the read landed at least one nameplate field on the WorkOrder
 * @param make         the manufacturer read off the nameplate (nullable)
 * @param model        the model number/name (nullable)
 * @param serial       the serial number (nullable)
 * @param message      a caller-friendly one-liner (always present)
 * @param attachmentId the stored photo's Attachment id (so the FE can reference it; never null)
 */
public record EquipmentPhotoResponse(
        boolean enriched,
        String make,
        String model,
        String serial,
        String message,
        UUID attachmentId) {
}
