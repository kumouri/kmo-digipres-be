package com.kumouri.kmodigipresbe.module.chairfill.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.controller.dto.WaitlistBoardDTO;
import com.kumouri.kmodigipresbe.module.chairfill.controller.dto.WaitlistBoardEntryDTO;
import com.kumouri.kmodigipresbe.module.chairfill.controller.dto.WaitlistOfferDTO;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntryRepository;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOfferRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * ChairFill CF-5a — the staff-facing waitlist-board read. The gap-fill flow (CF-3) mints
 * {@link WaitlistEntry} (clients waiting) and {@code WaitlistOffer} (who's been offered what) rows but
 * exposes no admin read; this is that read, so the CF-5 board FE can show the salon's current gap-fill
 * state: the OPEN entries + the recent offers (with their OFFERED/CLAIMED/SUPERSEDED/EXPIRED status),
 * newest first.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET /chairfill/waitlist/board} — the one-shot envelope: OPEN entries + recent offers
 *       (the board FE's primary call). {@code offerLimit} caps the recent-offers slice (default 50).</li>
 *   <li>{@code GET /chairfill/waitlist/entries} — just the OPEN entries, newest join first.</li>
 *   <li>{@code GET /chairfill/waitlist/offers} — just the recent offers (all statuses), newest sent
 *       first, capped by {@code limit} (default 50).</li>
 * </ul>
 *
 * <h2>Gating (the {@code NoShowRiskController} / {@code SalonReviewReplyController} precedent)</h2>
 * <ul>
 *   <li>{@link ConditionalOnProperty}-gated on {@code kmosf.modules.chairfill.enabled} — so this
 *       controller is absent from the generated OpenAPI spec when the module is off (the HS /
 *       {@code NoShowRiskController} precedent, where {@code OpenApiEndpointIT} runs without the
 *       chairfill flag);</li>
 *   <li>per-tenant module membership via {@link TenantModuleRegistry#requireEnabled(String)} — a
 *       tenant without {@code chairfill} gets the shared {@code 1130}/{@code 1132} module-gate
 *       not-enabled response (the {@code 4202}/{@code 2700}/{@code 3930} posture);</li>
 *   <li>{@link RoleGuard#requireRole "STAFF"} — a board read is staff-only ({@code 1800} otherwise),
 *       matching the {@code SalonReviewReplyController} STAFF gate.</li>
 * </ul>
 *
 * <p>Purely additive: a read over the CF-3 collections that does not touch CF-1..CF-4 behavior. The
 * CF-5 error band reserves {@code 4245-4249} for future board-read growth; this read mints no new
 * code (it reuses the shared module-gate {@code 1130}/{@code 1132} and role-gate {@code 1800}).
 */
@RestController
@RequestMapping("/chairfill/waitlist")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@RequiredArgsConstructor
public class WaitlistBoardController {

    /** Default cap on the recent-offers slice when the caller supplies no {@code limit}. */
    static final int DEFAULT_OFFER_LIMIT = 50;

    private final WaitlistEntryRepository entries;
    private final WaitlistOfferRepository offers;
    private final TenantModuleRegistry modules;

    /** The one-shot board envelope: OPEN entries + recent offers (newest first), STAFF + module gated. */
    @GetMapping("/board")
    public Mono<WaitlistBoardDTO> board(
            @RequestParam(name = "offerLimit", required = false) Integer offerLimit) {
        int cap = normalizeLimit(offerLimit);
        return guard().then(TenantContextHolder.required().flatMap(ctx ->
                Mono.zip(
                        entries.findByTenantIdAndStatusOrderByCreatedAtDesc(
                                        ctx.tenantId(), WaitlistEntry.Status.OPEN)
                                .map(WaitlistBoardEntryDTO::from)
                                .collectList(),
                        offers.findByTenantIdOrderBySentAtDesc(ctx.tenantId())
                                .take(cap)
                                .map(WaitlistOfferDTO::from)
                                .collectList())
                        .map(t -> new WaitlistBoardDTO(t.getT1(), t.getT2()))));
    }

    /** Just the OPEN waitlist entries, newest join first. STAFF + module gated. */
    @GetMapping("/entries")
    public Flux<WaitlistBoardEntryDTO> openEntries() {
        return guard().thenMany(TenantContextHolder.required().flatMapMany(ctx ->
                entries.findByTenantIdAndStatusOrderByCreatedAtDesc(
                                ctx.tenantId(), WaitlistEntry.Status.OPEN)
                        .map(WaitlistBoardEntryDTO::from)));
    }

    /** Just the recent offers (all statuses), newest sent first, capped by {@code limit}. */
    @GetMapping("/offers")
    public Flux<WaitlistOfferDTO> recentOffers(
            @RequestParam(name = "limit", required = false) Integer limit) {
        int cap = normalizeLimit(limit);
        return guard().thenMany(TenantContextHolder.required().flatMapMany(ctx ->
                offers.findByTenantIdOrderBySentAtDesc(ctx.tenantId())
                        .take(cap)
                        .map(WaitlistOfferDTO::from)));
    }

    /** A non-positive or absent limit falls back to the default; the cap is purely a slice guard. */
    private static int normalizeLimit(Integer limit) {
        return (limit == null || limit <= 0) ? DEFAULT_OFFER_LIMIT : limit;
    }

    /** chairfill module loaded + enabled for the tenant, then STAFF — the order matches CF-4. */
    private Mono<Void> guard() {
        return modules.requireEnabled(ChairFillAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
