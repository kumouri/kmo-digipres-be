package com.kumouri.kmodigipresbe.module.quoting.service;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.repository.PriceBookRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import reactor.core.publisher.Mono;

/**
 * T8 (Home Services "QuoteNow") — per-tenant {@link PriceBook} CRUD (Q1). One book per tenant. The
 * upsert is an <strong>explicit-boolean</strong> find-then-update (never {@code switchIfEmpty(create)}
 * — the §9 invariant + the {@code CallbackController.upsertConfig} precedent): an existing book is
 * updated in place (preserving id/version/timestamps), else a fresh book is created.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code QuotingAutoConfiguration} when the module is
 * enabled (not component-scanned, so it never loads when {@code kmosf.modules.quoting} is off).
 */
public class PriceBookService {

    private final PriceBookRepository priceBooks;

    public PriceBookService(PriceBookRepository priceBooks) {
        this.priceBooks = priceBooks;
    }

    /** The tenant's price book; {@code 4431}/404 when none has been seeded yet. */
    public Mono<PriceBook> get() {
        return TenantContextHolder.required()
                .flatMap(ctx -> priceBooks.findByTenantId(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "No price book has been set up for this tenant", 4431, 404))));
    }

    /**
     * Upsert the tenant's price book (explicit-boolean — never {@code switchIfEmpty(create)}). The
     * incoming {@code patch} carries the new {@code name}/{@code currency}/{@code lineItems}/
     * diagnostic fee; server-managed fields (id/tenantId/version/timestamps) are preserved on an
     * existing row. {@code 4432}/400 when the body is null.
     */
    public Mono<PriceBook> upsert(PriceBook patch) {
        if (patch == null) {
            return Mono.error(new DigiPresBeException("Price book body is required", 4432, 400));
        }
        return TenantContextHolder.required().flatMap(ctx ->
                priceBooks.findByTenantId(ctx.tenantId())
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(existing -> {
                            PriceBook toSave;
                            if (existing.isPresent()) {
                                PriceBook book = existing.get();
                                if (patch.getName() != null) book.setName(patch.getName());
                                if (patch.getCurrency() != null) book.setCurrency(patch.getCurrency());
                                if (patch.getLineItems() != null) book.setLineItems(patch.getLineItems());
                                if (patch.getDiagnosticVisitLow() != null) {
                                    book.setDiagnosticVisitLow(patch.getDiagnosticVisitLow());
                                }
                                if (patch.getDiagnosticVisitHigh() != null) {
                                    book.setDiagnosticVisitHigh(patch.getDiagnosticVisitHigh());
                                }
                                toSave = book;
                            } else {
                                patch.setId(null);
                                patch.setTenantId(ctx.tenantId());
                                toSave = patch;
                            }
                            return priceBooks.save(toSave);
                        }));
    }
}
