package com.kumouri.kmodigipresbe.module.proposals;

import java.util.UUID;

/**
 * Request body for {@code POST /proposals/draft} (AI Proposal / SOW generator, band 4620-4639).
 * Validation (in {@link ProposalDraftService#draft}):
 * <ul>
 *   <li>{@code notes} — required; non-blank and ≤ {@code kmosf.modules.proposals.max-notes-chars}
 *       (default 8000) (4621 / 400);</li>
 *   <li>{@code contactId} / {@code companyId} / {@code dealId} — optional client/deal context carried
 *       onto the DRAFT {@code Quote} (and {@code contactId} onto the {@code PROPOSAL_DRAFTED} payload);</li>
 *   <li>{@code currency} — optional ISO currency for the Quote (defaults to the Quote's own default,
 *       USD).</li>
 * </ul>
 */
public record ProposalDraftRequest(
        String notes,
        UUID contactId,
        UUID companyId,
        UUID dealId,
        String currency
) {}
