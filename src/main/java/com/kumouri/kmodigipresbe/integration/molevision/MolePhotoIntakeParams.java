package com.kumouri.kmodigipresbe.integration.molevision;

/**
 * The optional homeowner-supplied text fields that accompany a photo submission (Phase 2 — NMM
 * "is this a mole?" photo triage, Feature B). Posted as text parts alongside the image part in a
 * {@code multipart/form-data} request; every field is nullable/blank-tolerant — the public tool
 * may ask for nothing but the photo.
 *
 * <p>The tenant is resolved from the path token <strong>only</strong> (never from these fields) —
 * the {@code ServiceRequestWidgetController} precedent. These fields seed the find-or-create
 * Contact + the Activity body so Rob has a callback handle; they are advisory, not authoritative.
 *
 * @param name    the homeowner's name, if supplied (nullable)
 * @param phone   a callback phone number, if supplied (nullable; the find-or-create Contact key
 *                when present)
 * @param email   a contact email, if supplied (nullable)
 * @param address the property address, if supplied (nullable)
 */
public record MolePhotoIntakeParams(
        String name,
        String phone,
        String email,
        String address) {

    /** Trims each field to null; an all-blank submission yields an all-null params object. */
    public static MolePhotoIntakeParams of(String name, String phone, String email, String address) {
        return new MolePhotoIntakeParams(
                trimToNull(name), trimToNull(phone), trimToNull(email), trimToNull(address));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
