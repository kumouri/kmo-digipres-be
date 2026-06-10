package com.kumouri.kmodigipresbe.module.quoting.model;

/**
 * T8 (Home Services "QuoteNow") — the lifecycle of a submitted {@link QuoteRequest}.
 *
 * <ul>
 *   <li>{@code NEW} — the homeowner just submitted; the office inbox shows it un-actioned.</li>
 *   <li>{@code ACCEPTED} — the homeowner accepted the estimate (via the public accept endpoint); a
 *       booking-link SMS was sent.</li>
 *   <li>{@code BOOKED} — the confirming visit is on the calendar (reserved for a future live
 *       Cal.com booking-confirmation hop; the demo path stops at ACCEPTED + the booking link).</li>
 *   <li>{@code DECLINED} — the homeowner declined.</li>
 * </ul>
 *
 * The states an accept may legally transition FROM are NEW (first accept) — a re-accept of an
 * already-ACCEPTED/BOOKED quote re-confirms idempotently (no second SMS); DECLINED is terminal.
 */
public enum QuoteStatus {
    NEW,
    ACCEPTED,
    BOOKED,
    DECLINED
}
