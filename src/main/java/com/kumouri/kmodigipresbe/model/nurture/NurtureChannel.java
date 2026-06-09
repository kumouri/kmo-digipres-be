package com.kumouri.kmodigipresbe.model.nurture;

/**
 * The outbound channel of a single nurture cadence step (E1 — Nurture / Cadence Engine).
 *
 * <p>Vertical-agnostic: a campaign mixes SMS and EMAIL steps freely. The runner dispatches an
 * {@code SMS} step via the reused {@code TwilioSmsService} and an {@code EMAIL} step via the reused
 * {@code EmailService}.
 */
public enum NurtureChannel {
    SMS,
    EMAIL
}
