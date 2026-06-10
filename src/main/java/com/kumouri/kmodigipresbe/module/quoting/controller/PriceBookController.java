package com.kumouri.kmodigipresbe.module.quoting.controller;

import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.service.PriceBookService;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * T8 (Home Services "QuoteNow") — per-tenant price-book admin (Q1). The price book is the
 * wrong-number-liability fence: a synthesized quote range is only as defensible as the book a human
 * reviewed and seeded. ADMIN-only read + upsert.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET /quoting/price-book} (ADMIN) → the tenant's {@link PriceBook} (4431 if none).</li>
 *   <li>{@code PUT /quoting/price-book} (ADMIN, body {@link PriceBook}) → the upserted book.</li>
 * </ul>
 *
 * <h2>Gating</h2>
 * Class {@code @ConditionalOnProperty(kmosf.modules.quoting)} (so it is absent from the OpenAPI spec
 * + returns 404 when the module is off — it is a default-OFF admin route the FE hand-writes types
 * for). Per-tenant membership via {@link TenantModuleRegistry#requireEnabled} (1130/1132) +
 * {@link RoleGuard#requireRole "ADMIN"} (1800).
 */
@RestController
@RequestMapping("/quoting/price-book")
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class PriceBookController {

    private final PriceBookService priceBookService;
    private final TenantModuleRegistry modules;

    public PriceBookController(PriceBookService priceBookService, TenantModuleRegistry modules) {
        this.priceBookService = priceBookService;
        this.modules = modules;
    }

    @GetMapping
    public Mono<PriceBook> get() {
        return adminGuard().then(priceBookService.get());
    }

    @PutMapping
    public Mono<PriceBook> upsert(@RequestBody PriceBook body) {
        return adminGuard().then(priceBookService.upsert(body));
    }

    private Mono<Void> adminGuard() {
        return modules.requireEnabled(QuotingAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"));
    }
}
