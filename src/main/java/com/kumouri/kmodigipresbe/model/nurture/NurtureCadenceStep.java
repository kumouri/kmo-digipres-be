package com.kumouri.kmodigipresbe.model.nurture;

/**
 * One ordered step in a campaign's multi-touch cadence (E1 — Nurture / Cadence Engine), embedded on
 * {@link NurtureCampaign}.
 *
 * <h2>Scheduling</h2>
 * Step {@code i} becomes due at {@code enrolledAt + Σ(offsetDays[0..i]) + appliedBackoffDays}, where
 * {@code appliedBackoffDays} accumulates each prior step's {@link #backoffDays} (so each successive
 * no-reply gap grows — the "with backoff" cadence). The runner computes the next {@code nextFireAt}
 * after each send from the <em>next</em> step's {@code offsetDays} plus the accumulated backoff.
 *
 * <h2>Templates</h2>
 * For an {@link NurtureChannel#SMS} step, {@link #smsBody} is required (the others may be null); for an
 * {@link NurtureChannel#EMAIL} step, {@link #emailSubject} and {@link #emailBody} are required. Simple
 * {@code {firstName}} / {@code {name}} placeholders are substituted from the contact. When
 * {@link #aiPersonalize} is true the runner best-effort rewrites the rendered copy via the reused
 * Anthropic seam — a budget/upstream failure degrades to the rendered template, never drops the send.
 *
 * @param stepIndex     0-based, contiguous within the campaign
 * @param channel       SMS or EMAIL
 * @param offsetDays    days after the prior step (or enrollment, for step 0) this step is due (>= 0)
 * @param smsBody       SMS template (required for SMS steps; nullable otherwise)
 * @param emailSubject  email subject template (required for EMAIL steps; nullable otherwise)
 * @param emailBody     email HTML body template (required for EMAIL steps; nullable otherwise)
 * @param aiPersonalize best-effort AI rewrite of the rendered copy
 * @param backoffDays   extra days added to the cadence gap after this step (cadence backoff; >= 0)
 */
public record NurtureCadenceStep(
        int stepIndex,
        NurtureChannel channel,
        int offsetDays,
        String smsBody,
        String emailSubject,
        String emailBody,
        boolean aiPersonalize,
        int backoffDays) {

    /** True iff this step carries the template fields its channel requires. */
    public boolean hasRequiredTemplate() {
        if (channel == NurtureChannel.SMS) {
            return smsBody != null && !smsBody.isBlank();
        }
        if (channel == NurtureChannel.EMAIL) {
            return emailSubject != null && !emailSubject.isBlank()
                    && emailBody != null && !emailBody.isBlank();
        }
        return false;
    }
}
