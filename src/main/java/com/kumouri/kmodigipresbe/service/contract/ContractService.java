package com.kumouri.kmodigipresbe.service.contract;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.Contract.Status;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.repository.contract.ContractRepository;
import com.kumouri.kmodigipresbe.repository.contract.ContractTemplateRepository;
import com.kumouri.kmodigipresbe.service.quote.QuoteService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the {@link Contract} lifecycle (Phase F — F.4, F-D3, F-D6).
 *
 * <h2>Quote-ACCEPTED → SOW spawn (F-D6)</h2>
 * {@link #spawnFromQuote} is the explicit idempotent endpoint — NOT an event subscriber.
 * Idempotency uses an <strong>explicit boolean branch</strong>:
 * <pre>
 *   contracts.existsByTenantIdAndQuoteIdAndTemplateId(…)
 *       .flatMap(exists -&gt; exists
 *               ? contracts.findFirstByTenantIdAndQuoteIdAndTemplateId(…)  // return existing
 *               : doCreate(…))                                               // create once
 * </pre>
 * This mirrors {@code ProjectService.convertFromDeal} (C-D4) and avoids the
 * reactive-empty-completion trap where {@code switchIfEmpty(create)} would
 * double-create.
 *
 * <h2>§9 invariant</h2>
 * {@code switchIfEmpty} is used ONLY for genuine not-found (errorCodes 3705, 3707).
 * No conditional create or spawn is gated by {@code switchIfEmpty}.
 *
 * <h2>Status machine</h2>
 * {@code DRAFT → SENT → SIGNED} (terminal-happy);
 * {@code DRAFT|SENT → VOIDED} (terminal). {@code SIGNED} and {@code VOIDED} are
 * terminal — no out-transition. The full illegal-transition set is enforced here
 * via {@link #ILLEGAL_TRANSITIONS}. Moving {@code SENT→SIGNED} via the API is
 * illegal; only the verified Documenso webhook may do so.
 */
@Service
@RequiredArgsConstructor
public class ContractService {

    /**
     * Illegal status transitions (F-D3 + the {@code ProjectService.ILLEGAL_STATUS_TRANSITIONS}
     * / {@code RecurringInvoiceService.ILLEGAL_TRANSITIONS} pattern).
     *
     * <p>{@code SIGNED} is fully terminal — no out-transition. {@code VOIDED} is
     * fully terminal. {@code DRAFT→SIGNED} is also illegal via the API (only the
     * Documenso webhook may move to SIGNED). Self-transitions on any status are
     * rejected as defensive.
     */
    private static final Set<String> ILLEGAL_TRANSITIONS = Set.of(
            // SIGNED is terminal
            "SIGNED->DRAFT",
            "SIGNED->SENT",
            "SIGNED->VOIDED",
            "SIGNED->SIGNED",
            // VOIDED is terminal
            "VOIDED->DRAFT",
            "VOIDED->SENT",
            "VOIDED->SIGNED",
            "VOIDED->VOIDED",
            // DRAFT cannot jump directly to SIGNED (only via webhook)
            "DRAFT->SIGNED",
            // self-transitions (defensive)
            "DRAFT->DRAFT",
            "SENT->DRAFT",
            "SENT->SENT"
    );

    private final ContractRepository contracts;
    private final ContractTemplateRepository templates;
    private final QuoteService quoteService;
    private final DomainEventPublisher events;

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public Flux<Contract> findAll() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> contracts.findAllByTenantId(ctx.tenantId()));
    }

    public Mono<Contract> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> contracts.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Contract not found", 3707, 404)));
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    /**
     * Manual contract create (incl. amendments via {@link Contract#getParentContractId()}).
     * Title is required (3703). Sets {@code id=null}, {@code tenantId}, default status
     * DRAFT, and publishes {@code CONTRACT_CREATED} (advisory).
     */
    public Mono<Contract> create(Contract body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getTitle() == null || body.getTitle().isBlank()) {
                return Mono.error(new DigiPresBeException(
                        "Contract title is required", 3703, 400));
            }
            body.setId(null);
            body.setTenantId(ctx.tenantId());
            if (body.getStatus() == null) {
                body.setStatus(Status.DRAFT);
            }
            if (body.getKind() == null) {
                body.setKind(ContractTemplate.Kind.GENERIC);
            }
            if (body.getVariables() == null) {
                body.setVariables(Map.of());
            }
            return contracts.save(body).flatMap(saved -> {
                events.publish(DomainEvent.of(
                        DomainEventType.CONTRACT_CREATED,
                        saved.getTenantId(), saved.getId(),
                        Map.of("title", saved.getTitle())));
                return Mono.just(saved);
            });
        });
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    public Mono<Contract> update(UUID id, Contract patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getTitle() != null) {
                if (patch.getTitle().isBlank()) {
                    return Mono.error(new DigiPresBeException(
                            "Contract title is required", 3703, 400));
                }
                existing.setTitle(patch.getTitle());
            }
            if (patch.getKind() != null)             existing.setKind(patch.getKind());
            if (patch.getTemplateId() != null)       existing.setTemplateId(patch.getTemplateId());
            if (patch.getParentContractId() != null) existing.setParentContractId(patch.getParentContractId());
            if (patch.getDealId() != null)           existing.setDealId(patch.getDealId());
            if (patch.getContactId() != null)        existing.setContactId(patch.getContactId());
            if (patch.getCompanyId() != null)        existing.setCompanyId(patch.getCompanyId());
            if (patch.getQuoteId() != null)          existing.setQuoteId(patch.getQuoteId());
            if (patch.getVariables() != null)        existing.setVariables(patch.getVariables());
            if (patch.getVoidReason() != null)       existing.setVoidReason(patch.getVoidReason());
            return contracts.save(existing);
        });
    }

    // -------------------------------------------------------------------------
    // Status machine
    // -------------------------------------------------------------------------

    /**
     * Sets the contract status, enforcing the {@link #ILLEGAL_TRANSITIONS} set (→ 3709)
     * and requiring a {@code voidReason} when voiding (→ 3706).
     *
     * <p>Note: {@code SENT→SIGNED} is not reachable here — "DRAFT→SIGNED" and
     * "SENT→SIGNED" would reach the illegal-transition guard; only the Documenso
     * webhook may write {@code SIGNED}.
     */
    public Mono<Contract> setStatus(UUID id, Status target, String voidReason) {
        return findById(id).flatMap(existing -> {
            String transitionKey = existing.getStatus().name() + "->" + target.name();
            if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                return Mono.error(new DigiPresBeException(
                        "Status transition " + transitionKey + " is not permitted", 3709, 409));
            }
            if (target == Status.VOIDED && (voidReason == null || voidReason.isBlank())) {
                return Mono.error(new DigiPresBeException(
                        "voidReason is required when voiding a contract", 3706, 400));
            }
            existing.setStatus(target);
            if (target == Status.VOIDED && voidReason != null) {
                existing.setVoidReason(voidReason);
            }
            return contracts.save(existing).flatMap(saved -> {
                if (target == Status.VOIDED) {
                    events.publish(DomainEvent.of(
                            DomainEventType.CONTRACT_VOIDED,
                            saved.getTenantId(), saved.getId(),
                            Map.of("voidReason", saved.getVoidReason() != null
                                    ? saved.getVoidReason() : "")));
                }
                return Mono.just(saved);
            });
        });
    }

    // -------------------------------------------------------------------------
    // Quote-ACCEPTED → SOW spawn (F-D6)
    // -------------------------------------------------------------------------

    /**
     * Spawns a {@link Contract} from an ACCEPTED {@link Quote} and an active
     * {@link ContractTemplate} (F-D6).
     *
     * <h2>Idempotency — explicit boolean (NEVER switchIfEmpty(create))</h2>
     * Mirrors {@code ProjectService.convertFromDeal} (C-D4). The probe is:
     * <pre>
     *   contracts.existsByTenantIdAndQuoteIdAndTemplateId(tenantId, quoteId, templateId)
     *       .flatMap(exists -&gt; exists
     *               ? contracts.findFirstByTenantIdAndQuoteIdAndTemplateId(…)  // idempotent return
     *               : doCreate(ctx, quote, template))                           // create once
     * </pre>
     *
     * @param quoteId    the Quote that must be in {@link Quote.Status#ACCEPTED} (→ 3704 otherwise)
     * @param templateId an active ContractTemplate (→ 3705 if missing or inactive)
     * @return the existing or newly-created Contract
     */
    public Mono<Contract> spawnFromQuote(UUID quoteId, UUID templateId) {
        return TenantContextHolder.required().flatMap(ctx ->
                // Load the Quote via QuoteService (tenant-scoped; not-found → 2200,404 from QuoteService)
                quoteService.findById(quoteId)
                        .flatMap(quote -> {
                            // Quote must be ACCEPTED (F-D6 → 3704)
                            if (quote.getStatus() != Quote.Status.ACCEPTED) {
                                return Mono.error(new DigiPresBeException(
                                        "Quote must be in ACCEPTED status to spawn a contract",
                                        3704, 409));
                            }
                            // Load active template — switchIfEmpty here is genuine not-found (3705)
                            return templates.findByTenantIdAndIdAndActiveTrue(ctx.tenantId(), templateId)
                                    .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                            "ContractTemplate not found or inactive", 3705, 404)))
                                    .flatMap(template ->
                                            // Explicit-boolean idempotency probe — NEVER switchIfEmpty(create)
                                            contracts.existsByTenantIdAndQuoteIdAndTemplateId(
                                                            ctx.tenantId(), quoteId, templateId)
                                                    .flatMap(exists -> {
                                                        if (exists) {
                                                            // Idempotent: return the existing contract
                                                            return contracts.findFirstByTenantIdAndQuoteIdAndTemplateId(
                                                                    ctx.tenantId(), quoteId, templateId);
                                                        } else {
                                                            return doCreate(ctx.tenantId(), quote, template);
                                                        }
                                                    })
                                    );
                        })
        );
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(c -> contracts.deleteById(c.getId()));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    /**
     * Creates a new DRAFT Contract from the Quote + Template snapshot (F-D6).
     *
     * <ul>
     *   <li>Copies {@code dealId}/{@code contactId}/{@code companyId}/{@code quoteId}
     *       from the Quote.</li>
     *   <li>Snapshots {@code variables} from the Quote's fields + line items so later
     *       template edits never change an executed contract.</li>
     *   <li>Renders the title from {@code template.defaultTitle} (jmustache, via the
     *       template string) if non-blank; falls back to {@code template.name}.</li>
     *   <li>{@code kind} copied from {@code template.kind}; {@code status=DRAFT}.</li>
     * </ul>
     */
    private Mono<Contract> doCreate(UUID tenantId, Quote quote, ContractTemplate template) {
        return Mono.fromCallable(() -> {
            // Build the variables snapshot from the Quote (snapshotted at spawn time — F-D3)
            Map<String, Object> variables = buildVariables(quote);

            // Render the title: expand template.defaultTitle if non-blank, else fall back to template.name
            String title;
            if (template.getDefaultTitle() != null && !template.getDefaultTitle().isBlank()) {
                try {
                    title = com.samskivert.mustache.Mustache.compiler()
                            .escapeHTML(false)
                            .defaultValue("")
                            .compile(template.getDefaultTitle())
                            .execute(variables);
                } catch (com.samskivert.mustache.MustacheException ex) {
                    throw new DigiPresBeException(
                            "Contract title template rendering failed: " + ex.getMessage(), 3702, 400);
                }
            } else {
                title = template.getName();
            }

            return Contract.builder()
                    .id(null)
                    .tenantId(tenantId)
                    .title(title)
                    .kind(template.getKind() != null ? template.getKind() : ContractTemplate.Kind.GENERIC)
                    .status(Status.DRAFT)
                    .templateId(template.getId())
                    .dealId(quote.getDealId())
                    .contactId(quote.getContactId())
                    .companyId(quote.getCompanyId())
                    .quoteId(quote.getId())
                    .variables(variables)
                    .build();
        }).flatMap(contract -> contracts.save(contract).flatMap(saved -> {
            events.publish(DomainEvent.of(
                    DomainEventType.CONTRACT_CREATED,
                    saved.getTenantId(), saved.getId(),
                    Map.of(
                            "title", saved.getTitle(),
                            "quoteId", quoteId(quote),
                            "templateId", saved.getTemplateId().toString())));
            return Mono.just(saved);
        }));
    }

    /**
     * Builds the jmustache variable snapshot from a Quote. The snapshot is
     * taken at spawn time so later edits to the template or quote never mutate
     * an executed contract (F-D3 legal-integrity requirement).
     */
    private Map<String, Object> buildVariables(Quote quote) {
        Map<String, Object> vars = new HashMap<>();
        if (quote.getId() != null)         vars.put("quoteId", quote.getId().toString());
        if (quote.getDealId() != null)     vars.put("dealId", quote.getDealId().toString());
        if (quote.getContactId() != null)  vars.put("contactId", quote.getContactId().toString());
        if (quote.getCompanyId() != null)  vars.put("companyId", quote.getCompanyId().toString());
        if (quote.getCurrency() != null)   vars.put("currency", quote.getCurrency());
        if (quote.getLineItems() != null)  vars.put("lineItems", quote.getLineItems());
        if (quote.getNotes() != null)      vars.put("notes", quote.getNotes());
        if (quote.getTerms() != null)      vars.put("terms", quote.getTerms());
        if (quote.getTotal() != null)      vars.put("total", quote.getTotal().toPlainString());
        if (quote.getSubtotal() != null)   vars.put("subtotal", quote.getSubtotal().toPlainString());
        return vars;
    }

    private String quoteId(Quote quote) {
        return quote.getId() == null ? "" : quote.getId().toString();
    }
}
