package com.kumouri.kmodigipresbe.module.proposals;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * The prose half of an AI-drafted SOW (AI Proposal / SOW generator, band 4620-4639). "A SOW is a
 * priced {@link com.kumouri.kmodigipresbe.model.quote.Quote} with prose" — the priced line items +
 * computed totals live on a DRAFT {@code Quote} (created via the UNCHANGED {@code QuoteService.create}),
 * and the four narrative sections live here, linked by {@link #quoteId}.
 *
 * <h2>Why a thin sibling, not new {@code Quote} fields (prose-storage decision)</h2>
 * {@code Quote} carries only two free-text fields ({@code notes}, {@code terms}) — not enough to hold
 * the four independently-editable SOW sections (scope / deliverables / assumptions / timeline) the
 * SOW-FE proposal editor needs to surface and re-edit separately. Rather than overload {@code notes}
 * with a delimited blob (lossy, un-editable) or mutate the shipped {@code Quote} model (which must stay
 * empty-diff vs {@code main} — the reused-core acceptance bar), the prose is a small additive
 * {@code SowDraft} document keyed by {@code quoteId}. The {@code Quote} model is untouched.
 *
 * <h2>{@code aiApplied} — "AI is triage, not truth"</h2>
 * {@code aiApplied=true} when the Anthropic draft call produced usable structured output;
 * {@code aiApplied=false} when the model was unavailable / over budget / returned a blank or
 * unparseable answer, in which case the draft is materialized empty (a human fills it in). The draft
 * is <strong>always</strong> persisted either way — an AI failure never blocks a SOW draft from being
 * created (the {@code MoleVisionService} / {@code VoicemailExtractionService} best-effort posture).
 *
 * <p>System-adjacent CRM entity — {@code TenantScoped} for tenant isolation. (Unlike the AR
 * {@code DunningLog} idempotency ledger this is genuine drafted content, but the proposals module has
 * no audit-event need, so it stays a plain {@code TenantScoped} document — no
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}.)
 */
@Document("sow_drafts")
@CompoundIndex(name = "tenant_quote_idx", def = "{ 'tenantId': 1, 'quoteId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SowDraft implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; the tenant-isolation key. */
    private UUID tenantId;

    /** The DRAFT {@link com.kumouri.kmodigipresbe.model.quote.Quote} this prose belongs to. */
    private UUID quoteId;

    /** Project scope narrative — what the engagement covers. */
    private String scope;

    /** Concrete deliverables the client receives. */
    private String deliverables;

    /** Assumptions / out-of-scope caveats the pricing depends on. */
    private String assumptions;

    /** Timeline / phasing prose. */
    private String timeline;

    /**
     * {@code true} iff the Anthropic draft produced usable output; {@code false} when the draft was
     * materialized empty after an AI failure / budget-exhaustion / blank-or-unparseable answer (a
     * human then fills the sections in). The draft is persisted either way.
     */
    private boolean aiApplied;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
