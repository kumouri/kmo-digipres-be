package com.kumouri.kmodigipresbe.module.chairfill.reviewboost;

/**
 * T6 Salon "ReviewBoost" — a read-back of the salon's ReviewBoost wiring, so the dashboard can show
 * whether the feature is actually configured (green/amber) without the manager hunting through
 * integration settings. Pure read; no new config store.
 *
 * <h2>What it reflects (all already-shipped knobs — T6 mints no config model)</h2>
 * <ul>
 *   <li>{@link #reviewLinkConfigured}/{@link #reviewLink} — the per-tenant Google review link the E3
 *       {@code ReviewRequestSenderJob} sends ({@code IntegrationConnection(twilio).config["reviewLink"]});
 *       a salon with no link sends no request, so this is the "is ReviewBoost wired?" signal.</li>
 *   <li>{@link #senderEnabled} — the effective {@code kmosf.modules.review-engine.sender-enabled} flag
 *       (DEFAULT-OFF; the no-incentive request SMS only goes out when a deployment opts in).</li>
 *   <li>{@link #sentimentRefineEnabled} — the effective {@code kmosf.review-engine.ai-refine-enabled} flag
 *       (DEFAULT-OFF; rating-based sentiment is always computed, AI refine is opt-in).</li>
 *   <li>{@link #negativeAlertEnabled} — the effective {@code kmosf.review-engine.negative-alert-enabled}
 *       flag (DEFAULT-OFF; the manager alert on a negative review).</li>
 * </ul>
 *
 * @param reviewLinkConfigured   whether a non-blank Google review link is set for this tenant
 * @param reviewLink             the configured link, or {@code null} if none (never fabricated)
 * @param senderEnabled          the effective default-OFF review-request sender flag
 * @param sentimentRefineEnabled the effective default-OFF AI sentiment-refine flag
 * @param negativeAlertEnabled   the effective default-OFF negative-review manager-alert flag
 */
public record ReviewBoostConfigDTO(
        boolean reviewLinkConfigured,
        String reviewLink,
        boolean senderEnabled,
        boolean sentimentRefineEnabled,
        boolean negativeAlertEnabled) {
}
