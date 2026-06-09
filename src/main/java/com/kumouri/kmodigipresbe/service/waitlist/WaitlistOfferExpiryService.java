package com.kumouri.kmodigipresbe.service.waitlist;

import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineOfferRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * E4 — the stale-offer expiry sweeper. The generic sibling of ChairFill CF-3's
 * {@code WaitlistOfferExpiryService} (which stays byte-equivalent), with one deliberate divergence:
 * <strong>no {@code @Scheduled} live job by default</strong> (design directive #3 — "No standalone
 * scheduled live-SMS job by default; the engine is consumer-triggered + module-gated, so it's dormant
 * until a consumer calls it"). Only the visible-for-test {@link #sweepOnce()} is exposed; a consumer / ops
 * that wants periodic hygiene can call it from its own scheduler.
 *
 * <p>The inbound-YES path ({@code WaitlistClaimEngine.resolveOpenOffer}) already filters out
 * past-{@code expiresAt} offers, so an expired offer is never wrongly claimable — but nothing otherwise
 * transitions a stale {@code OFFERED} {@link WaitlistOffer} to the {@link WaitlistOffer.Status#EXPIRED}
 * terminal status. This sweep flips every {@code OFFERED} offer whose {@code expiresAt} is in the past to
 * {@code EXPIRED} — <strong>pure ledger hygiene, not a claimability change</strong> (the claim gate is
 * untouched at any cadence).
 *
 * <p><strong>Blast radius zero + best-effort:</strong> only ACTIVE tenants whose {@code enabledModules}
 * contains {@code waitlist} are scanned; a per-tenant or per-offer failure is caught + logged so one bad
 * row never aborts the rest; a lost optimistic-lock race (a concurrent inbound YES just claimed the offer)
 * simply skips that row — an EXPIRED never clobbers a CLAIMED/SUPERSEDED.
 */
@Slf4j
public class WaitlistOfferExpiryService {

    private final TenantRepository tenantRepository;
    private final WaitlistEngineOfferRepository offerRepository;

    public WaitlistOfferExpiryService(TenantRepository tenantRepository,
                                      WaitlistEngineOfferRepository offerRepository) {
        this.tenantRepository = tenantRepository;
        this.offerRepository = offerRepository;
    }

    /**
     * Runs one full sweep across all ACTIVE waitlist-enabled tenants and returns the total number of offers
     * flipped to {@code EXPIRED}, so an IT (or a consumer's own scheduler) can drive it deterministically
     * (the CF-3 {@code WaitlistOfferExpiryService.sweepOnce()} posture). A per-tenant failure is logged and
     * contributes 0.
     */
    public Mono<Long> sweepOnce() {
        Instant now = Instant.now();
        return tenantRepository.findAll()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE
                        && t.getEnabledModules() != null
                        && t.getEnabledModules().contains(GapFillEngine.MODULE_KEY))
                .flatMap(tenant -> sweepTenant(tenant.getId(), now)
                        .onErrorResume(err -> {
                            log.warn("E4 offer-expiry sweep failed for tenant {}: {}",
                                    tenant.getId(), err.toString());
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum);
    }

    /**
     * Flip this tenant's past-{@code expiresAt} {@code OFFERED} offers to {@code EXPIRED}. The derived
     * finder carries the explicit {@code tenantId} (the marker does not auto-scope derived finders), so no
     * request context is needed. Each flip is best-effort: a lost optimistic-lock race (a concurrent claim
     * just moved the row to CLAIMED/SUPERSEDED) is skipped, not retried, so the sweep never overwrites a
     * winner.
     */
    private Mono<Long> sweepTenant(UUID tenantId, Instant now) {
        return offerRepository
                .findByTenantIdAndStatusAndExpiresAtBefore(tenantId, WaitlistOffer.Status.OFFERED, now)
                .flatMap(offer -> offerRepository.save(offer.toBuilder()
                                .status(WaitlistOffer.Status.EXPIRED).build())
                        .thenReturn(1L)
                        .onErrorResume(err -> {
                            log.debug("E4 offer-expiry skip for offer {} (tenant {}): {}",
                                    offer.getId(), tenantId, err.toString());
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum);
    }
}
