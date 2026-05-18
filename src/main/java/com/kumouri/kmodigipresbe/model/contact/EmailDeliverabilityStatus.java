package com.kumouri.kmodigipresbe.model.contact;

/**
 * Coarse-grained deliverability state for a {@link Contact}'s primary email
 * channel, as reported by inbound Postmark bounce/spam webhooks (Phase H.4).
 *
 * <p>Stored as an additive-nullable field on {@link Contact#emailDeliverability}.
 * {@code null} means no bounce/spam signal has been received — treat as
 * deliverable. Values are advisory; suppression policy is a downstream concern
 * (automation rules / sequence-engine opt-out logic).
 *
 * <ul>
 *   <li>{@link #OK} — an explicit "deliverable" signal has been set (e.g. after
 *       a manual resubscription). Not set automatically by the webhook — the webhook
 *       only sets {@code BOUNCED} or {@code SPAM_COMPLAINED}.</li>
 *   <li>{@link #BOUNCED} — a Postmark {@code RecordType=Bounce} webhook was received
 *       for this contact. Persistent (hard or soft bounce — callers should check
 *       {@code BounceType} on the {@link com.kumouri.kmodigipresbe.model.communication.EmailEngagement}
 *       payload for further classification).</li>
 *   <li>{@link #SPAM_COMPLAINED} — a Postmark {@code RecordType=SpamComplaint} webhook
 *       was received for this contact.</li>
 * </ul>
 */
public enum EmailDeliverabilityStatus {
    OK,
    BOUNCED,
    SPAM_COMPLAINED
}
