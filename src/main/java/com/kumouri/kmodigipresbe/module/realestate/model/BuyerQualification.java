package com.kumouri.kmodigipresbe.module.realestate.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * Real Estate Concierge (RE-2) — the buyer-qualification fields the concierge accumulates across a
 * {@link ConciergeConversation} (RE-2 §5 / decision 3). Embedded on the conversation so qualification
 * progress is durable turn-to-turn; the materialized {@code Deal} (RE-2) is the CRM projection the
 * unchanged nightly {@code LeadScoringV2Service} then tiers.
 *
 * <p>Every field is nullable / best-effort: a Claude extraction over the conversation-so-far fills in
 * what the buyer has revealed; a degraded/blank extraction simply leaves fields null (the conversation
 * is never dropped — RE-2 HARD GATE 3). Fields accumulate (a later turn never clears an earlier
 * non-null value — see {@code QualificationService.merge}).
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BuyerQualification {

    /** Whether the buyer is looking to buy or sell — drives the Deal title / framing. */
    public enum Intent { BUY, SELL }

    /** The buyer's budget / target price (USD), e.g. "around 450k" → 450000. Null until revealed. */
    private BigDecimal budget;

    /** The buyer's timeline as a free-text phrase, e.g. "60 days", "this spring", "no rush". Nullable. */
    private String timeline;

    /** Financing posture as a free-text phrase, e.g. "pre-approved", "cash", "needs a lender". Nullable. */
    private String financing;

    /** True when the buyer indicated mortgage pre-approval; null when unknown. */
    private Boolean preApproved;

    /** Buy vs. sell intent; defaults to BUY framing when unknown (the concierge is buyer-facing). Nullable. */
    private Intent intent;

    /** True once enough signal (a budget) has materialized a {@code Deal}; advisory flag for the router. */
    @Builder.Default
    private boolean dealMaterialized = false;
}
