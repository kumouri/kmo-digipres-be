package com.kumouri.kmodigipresbe.model.integration;

/**
 * The generic attribution dimension for the E3 Review Engine — what a {@link ReviewRequest} (and a
 * review insights rollup) is attributed to. Deliberately vertical-agnostic so the same engine serves
 * salon ("ReviewBoost" → attribute to a stylist/staff member) and home ("QuoteCloser" review leg →
 * attribute to a job/project) without any vertical hardcoding.
 *
 * <ul>
 *   <li>{@code STAFF} — a staff member (salon: the stylist who did the visit; the
 *       {@code BOOKING_COMPLETED} payload's {@code staffMemberId}).</li>
 *   <li>{@code PROJECT} — a delivery project/job (home: the {@code MILESTONE_COMPLETED} payload's
 *       {@code projectId}).</li>
 *   <li>{@code OTHER} — any future attribution target; the engine treats it generically.</li>
 * </ul>
 *
 * <p>Stored as a String on {@link ReviewRequest} (part of the creation-idempotency unique index) and
 * accepted as a path segment by the insights controller (an unparseable value → {@code 4340}).
 */
public enum ReviewSubjectType {
    STAFF,
    PROJECT,
    OTHER
}
