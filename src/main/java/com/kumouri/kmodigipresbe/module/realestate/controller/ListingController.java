package com.kumouri.kmodigipresbe.module.realestate.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — authenticated agent CRUD for {@link Listing}.
 *
 * <p>Gating mirrors the {@code NoShowRiskController}/{@code SalonReviewReplyController} precedent:
 * {@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)} (absent from the OpenAPI spec when the
 * module is off) + per-tenant module membership via {@link TenantModuleRegistry#requireEnabled} (the
 * shared 1130/1132 module-gate codes) + {@link RoleGuard#requireRole "STAFF"} (1800). Tenant is resolved
 * from the request context, so a listing is always scoped to the caller's tenant ({@code 4253} on a
 * missing/not-owned listing).
 *
 * <p>The base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so these map to
 * {@code /api/v1/realestate/listings...}.
 */
@RestController
@RequestMapping("/realestate/listings")
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@RequiredArgsConstructor
public class ListingController {

    private final ListingService listings;
    private final TenantModuleRegistry modules;

    @PostMapping
    public Mono<Listing> create(@RequestBody Listing body) {
        return guard().then(listings.create(body));
    }

    @GetMapping
    public Flux<Listing> list() {
        return guard().thenMany(listings.list());
    }

    @GetMapping("/{id}")
    public Mono<Listing> get(@PathVariable UUID id) {
        return guard().then(listings.get(id));
    }

    @PutMapping("/{id}")
    public Mono<Listing> update(@PathVariable UUID id, @RequestBody Listing body) {
        return guard().then(listings.update(id, body));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
