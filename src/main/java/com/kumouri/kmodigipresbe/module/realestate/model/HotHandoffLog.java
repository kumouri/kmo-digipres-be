package com.kumouri.kmodigipresbe.module.realestate.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-2) — the idempotency ledger for the hot-handoff (RE-2 / decision 3). One row
 * per {@code (tenant, deal)} that has been handed off to the agent, inserted <strong>FIRST</strong> (unique
 * {@code tenant_deal_idx}) so a re-fired {@code LEAD_SCORE_UPDATED} (a nightly re-score, a restart, a
 * concurrent emit) loses on a {@code DuplicateKeyException} and does ZERO duplicate notify work — the
 * {@code RiskTieredPreventionService.ReminderLog} / {@code TwilioVoicemailEvent} ledger-insert-FIRST
 * precedent.
 *
 * <p>{@code TenantScoped} for isolation. Not {@code Auditable} (an automation ledger record).
 */
@Document("re_hot_handoff_log")
@CompoundIndex(name = "tenant_deal_idx", def = "{ 'tenantId': 1, 'dealId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class HotHandoffLog implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The concierge-sourced Deal that flipped HOT — the unique handoff key. */
    private UUID dealId;

    /** The buyer contact the score updated for. */
    private UUID contactId;

    /** The originating listing (from the Deal's concierge customFields). Nullable. */
    private UUID listingId;

    /** The score that triggered the handoff. */
    private double score;

    private Instant handedOffAt;

    @CreatedDate
    private Instant createdAt;
}
