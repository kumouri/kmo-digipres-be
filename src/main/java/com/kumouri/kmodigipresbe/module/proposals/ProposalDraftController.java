package com.kumouri.kmodigipresbe.module.proposals;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * AI Proposal / SOW generator (band 4620-4639) — {@code POST /proposals/draft} drafts a scoped,
 * line-item-priced SOW from discovery notes; {@code GET /proposals/{id}} fetches a drafted SOW (the
 * DRAFT {@link Quote} + its {@link SowDraft} prose).
 *
 * <h2>Module gate — DEFAULT-OFF</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.proposals", name="enabled",
 * matchIfMissing=false)}: this bean does not exist when the proposals module is off, so the routes are
 * absent from the OpenAPI spec and 404 for non-proposals deployments — the {@code ArAgingController} /
 * {@code NoShowRiskController} precedent (module-gated controllers absent when the module flag is off).
 * Additionally, every handler runs {@link #proposalGuard()} which performs the per-tenant membership
 * check (the in-range {@code 4620}/404 parity code — a tenant that hasn't opted the module in gets
 * {@code 4620}, mirroring the AR {@code 4600} posture but surfaced in-band here) + the STAFF
 * {@link RoleGuard} ({@code 1800}/403). Defense in depth: the {@code @ConditionalOnProperty} gates the
 * deployment, the membership check gates the tenant.
 *
 * <h2>{@code POST /proposals/draft} — {@code @IdempotentRoute}</h2>
 * A draft is intentionally re-invocable at the domain level (no find-or-create ledger), but the POST
 * carries a real external effect (an Anthropic spend + a persisted DRAFT Quote), so it is an
 * {@link IdempotentRoute}: a retried {@code Idempotency-Key} replays the stored 201 without a second
 * draft (the {@code POST /invoices} / {@code POST /contracts/.../spawn-contract} posture). Returns 201
 * with the drafted {@link ProposalDraftService.DraftResult}. Blank notes / over-max-length →
 * {@code 4621}/400 (in {@link ProposalDraftService#draft}, before any AI spend).
 *
 * <h2>§9 invariants</h2>
 * <ul>
 *   <li>{@code switchIfEmpty} is used <em>only</em> for genuine entity-not-found: the Quote-not-found
 *       {@code 2200} in {@link #get}. No {@code switchIfEmpty(create)} anywhere.</li>
 *   <li>Reactive: the guard + read run on the Netty event loop; the draft delegates to the service
 *       (its only blocking-free AI + Mongo work). No blocking I/O on the loop.</li>
 * </ul>
 */
@Slf4j
@RestController
@RequestMapping("/proposals")
@ConditionalOnProperty(prefix = "kmosf.modules.proposals", name = "enabled", matchIfMissing = false)
public class ProposalDraftController {

    private final ProposalDraftService draftService;
    private final QuoteRepository quotes;
    private final SowDraftRepository sowDrafts;
    private final TenantRepository tenants;
    private final SowPdfService sowPdfService;

    public ProposalDraftController(
            ProposalDraftService draftService,
            QuoteRepository quotes,
            SowDraftRepository sowDrafts,
            TenantRepository tenants,
            SowPdfService sowPdfService) {
        this.draftService = draftService;
        this.quotes = quotes;
        this.sowDrafts = sowDrafts;
        this.tenants = tenants;
        this.sowPdfService = sowPdfService;
    }

    // ── POST /proposals/draft ──────────────────────────────────────────────────────────────────

    /**
     * Drafts a SOW from discovery notes → a DRAFT {@link Quote} (priced) + {@link SowDraft} prose.
     * Module-gated + STAFF + {@link IdempotentRoute}. 201 on success.
     */
    @PostMapping("/draft")
    @ResponseStatus(HttpStatus.CREATED)
    @IdempotentRoute
    public Mono<ProposalDraftService.DraftResult> draft(@RequestBody ProposalDraftRequest req) {
        return proposalGuard().then(Mono.defer(() ->
                draftService.draft(
                        req.notes(), req.contactId(), req.companyId(), req.dealId(), req.currency())));
    }

    // ── GET /proposals/{id} ────────────────────────────────────────────────────────────────────

    /**
     * Fetches a drafted SOW by its DRAFT {@link Quote} id: the tenant-scoped Quote + its
     * {@link SowDraft} prose (null prose if the Quote was not produced by this module). Module-gated +
     * STAFF. The Quote lookup is tenant-scoped ({@code QuoteRepository.findByTenantIdAndId}); a miss is
     * a genuine not-found {@code 2200}/404 (the only allowed {@code switchIfEmpty}).
     */
    @GetMapping("/{id}")
    public Mono<ProposalDraftService.DraftResult> get(@PathVariable UUID id) {
        return proposalGuard().then(TenantContextHolder.required().flatMap(ctx ->
                quotes.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Quote not found", 2200, 404)))
                        .flatMap(quote -> sowDrafts.findByTenantIdAndQuoteId(ctx.tenantId(), id)
                                .map(sow -> new ProposalDraftService.DraftResult(quote, sow))
                                .defaultIfEmpty(new ProposalDraftService.DraftResult(quote, null)))));
    }

    // ── GET /proposals/{id}/pdf ────────────────────────────────────────────────────────────────

    /**
     * Renders a drafted SOW to a single client-facing PDF: the priced DRAFT {@link Quote} (header +
     * line-item table + totals) plus the {@link SowDraft} prose (Scope / Deliverables / Assumptions /
     * Timeline), via {@link SowPdfService} (a sibling of {@code QuotePdfService} — its blocking iText
     * render runs on {@code boundedElastic}, off the Netty loop). Module-gated + STAFF.
     *
     * <p>Loads the tenant-scoped Quote ({@code QuoteRepository.findByTenantIdAndId}); a miss is the
     * genuine not-found {@code 2200}/404 (the {@link #get} posture). The linked {@link SowDraft} is
     * optional — a Quote with no draft renders the priced quote half only (the empty-draft
     * {@code switchIfEmpty} render-fallback). The {@code switchIfEmpty}s here are ONLY for genuine
     * not-found and the optional-prose render-fallback; no {@code switchIfEmpty(create)}.
     *
     * @return the rendered PDF bytes ({@code application/pdf})
     */
    @GetMapping(value = "/{id}/pdf", produces = MediaType.APPLICATION_PDF_VALUE)
    public Mono<byte[]> pdf(@PathVariable UUID id) {
        return proposalGuard().then(TenantContextHolder.required().flatMap(ctx ->
                quotes.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(new DigiPresBeException(
                                "Quote not found", 2200, 404)))
                        .flatMap(quote -> sowDrafts.findByTenantIdAndQuoteId(ctx.tenantId(), id)
                                // Optional prose half: render with the draft if present, else quote-only
                                // (null sow). flatMap skips on empty, so the no-draft case is its own
                                // switchIfEmpty render — NOT a defaultIfEmpty(null) (Reactor forbids null
                                // emissions). This switchIfEmpty is the optional-prose fallback, never a
                                // find-or-create.
                                .flatMap(sow -> sowPdfService.render(quote, sow))
                                .switchIfEmpty(Mono.defer(() -> sowPdfService.render(quote, null))))));
    }

    // ── common guard ──────────────────────────────────────────────────────────────────────────

    /**
     * Per-tenant membership ({@code 4620}/404 — the in-range parity code) + STAFF ({@code 1800}/403).
     * The {@code @ConditionalOnProperty} already gates the deployment (routes absent when the flag is
     * off); this gates the individual tenant. A non-member tenant gets {@code 4620}, never an enabled
     * code path — the AR {@code 4600} posture, surfaced in-band here so the gate is testable.
     */
    private Mono<Void> proposalGuard() {
        return TenantContextHolder.required()
                .flatMap(this::requireProposalsEnabled)
                .then(RoleGuard.requireRole("STAFF"));
    }

    private Mono<Void> requireProposalsEnabled(TenantContext ctx) {
        return tenants.findById(ctx.tenantId())
                .switchIfEmpty(Mono.error(new DigiPresBeException("Tenant not found", 1131, 404)))
                .flatMap(tenant -> tenant.getEnabledModules() != null
                        && tenant.getEnabledModules().contains(ProposalsAutoConfiguration.MODULE_KEY)
                        ? Mono.empty()
                        : Mono.error(new DigiPresBeException(
                                "Proposals module is not enabled for this tenant",
                                4620, 404)));
    }
}
