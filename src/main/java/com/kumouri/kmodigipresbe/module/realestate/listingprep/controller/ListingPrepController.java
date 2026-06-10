package com.kumouri.kmodigipresbe.module.realestate.listingprep.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.ListingPrepService;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPack;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Real Estate Concierge (T10 — Listing Prep Studio) — authenticated agent surface for one-click prep-pack
 * generation (MLS description + 4-week dated social calendar + email campaign) and the pack → approve / skip
 * queue.
 *
 * <p>Gating mirrors the RE-4 {@code ListingMarketingController} precedent:
 * {@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)} (absent from the OpenAPI spec when the
 * module is off) + per-tenant module membership via {@link TenantModuleRegistry#requireEnabled} + STAFF
 * {@link RoleGuard}. Tenant is resolved from the request context.
 *
 * <p><strong>No photo-upload endpoint here</strong> — photos are uploaded via the shipped RE-4
 * {@code POST /realestate/listings/{listingId}/marketing/photos}; the prep pack reads the same
 * {@code ListingPhoto}s. So there is no multipart handling in T10.
 *
 * <p><strong>Never auto-publish.</strong> Generation only ever produces a DRAFTED pack; the agent must call
 * {@code approve} (copy-ready, paste-out) — the RE-4 draft→approve posture. There is no endpoint that posts to
 * MLS/social/email (out of scope).
 *
 * <p>The base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so these map to
 * {@code /api/v1/realestate/listings/{listingId}/prep...} and {@code /api/v1/realestate/prep/packs...}.
 */
@RestController
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@RequiredArgsConstructor
public class ListingPrepController {

    private final ListingPrepService prep;
    private final TenantModuleRegistry modules;

    // ── Generate (→ DRAFTED) ─────────────────────────────────────────────────────

    /**
     * One-click generate: reuses the RE-4 vision feature read + MLS description + email, adds the net-new
     * 4-week dated social calendar, runs the Fair-Housing lint over all copy (a flagged calendar post is held
     * + safe-substituted), and persists a DRAFTED {@link ListingPrepPack} (never auto-published). Best-effort:
     * a Claude/vision failure yields a partial/degraded DRAFTED pack, never an error. A missing/not-owned
     * listing → {@code 4253}/404. The body is optional ({@code startDate} / {@code postsPerWeek} both nullable).
     */
    @PostMapping("/realestate/listings/{listingId}/prep/generate")
    public Mono<ListingPrepPack> generate(@PathVariable UUID listingId,
                                          @RequestBody(required = false) ListingPrepGenerateRequest body) {
        ListingPrepGenerateRequest req = body != null ? body : new ListingPrepGenerateRequest(null, null);
        return guard().then(prep.generate(listingId, req.startDate(), req.postsPerWeek()));
    }

    /** Lists a listing's prep packs (history), most-recent first. */
    @GetMapping("/realestate/listings/{listingId}/prep/packs")
    public Flux<ListingPrepPack> listForListing(@PathVariable UUID listingId) {
        return guard().thenMany(prep.listForListing(listingId));
    }

    // ── Pack queue: list / get / approve / skip ──────────────────────────────────

    /** The tenant's DRAFTED prep packs (the review queue), most-recent first. */
    @GetMapping("/realestate/prep/packs")
    public Flux<ListingPrepPack> listDrafted() {
        return guard().thenMany(prep.listDrafted());
    }

    /** A single prep pack, tenant-scoped. {@code 4460} if no such pack for the tenant. */
    @GetMapping("/realestate/prep/packs/{id}")
    public Mono<ListingPrepPack> get(@PathVariable UUID id) {
        return guard().then(prep.get(id));
    }

    /**
     * Approves a DRAFTED pack → APPROVED (copy-ready, paste-out). {@code 4460} not-found; {@code 4461}/409 if
     * not DRAFTED.
     */
    @PostMapping("/realestate/prep/packs/{id}/approve")
    public Mono<ListingPrepPack> approve(@PathVariable UUID id) {
        return guard().then(prep.approve(id));
    }

    /** Skips a DRAFTED pack (discard) → SKIPPED. {@code 4460} not-found; {@code 4461}/409 if not DRAFTED. */
    @PostMapping("/realestate/prep/packs/{id}/skip")
    public Mono<ListingPrepPack> skip(@PathVariable UUID id) {
        return guard().then(prep.skip(id));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
