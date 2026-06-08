package com.kumouri.kmodigipresbe.module.chairfill.gapfill;

import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOfferRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.UUID;

/**
 * ChairFill CF-3 follow-up — the stale-offer expiry sweeper. The inbound-YES path
 * ({@link WaitlistClaimService#resolveOpenOffer}) already filters out past-{@code expiresAt} offers, so an
 * expired offer is never wrongly claimable — but nothing ever transitions a stale {@code OFFERED}
 * {@link WaitlistOffer} to the {@link WaitlistOffer.Status#EXPIRED} terminal status the enum already
 * defines. Without this sweep the offer ledger accumulates perpetually-{@code OFFERED} rows that are in
 * fact dead, muddying every "open offers" read and report. This periodically flips every {@code OFFERED}
 * offer whose {@code expiresAt} is in the past to {@code EXPIRED}.
 *
 * <p><strong>Pure ledger hygiene, not a claimability change.</strong> Whether a YES can win a slot is
 * decided by {@code resolveOpenOffer}'s {@code expiresAt} filter plus the slot-level {@code findAndModify}
 * (CF-3 D2) — both untouched. This sweep only makes the persisted {@code status} reflect the reality that
 * {@code expiresAt} already implies, so an expired-but-still-{@code OFFERED} ghost row stops showing up as
 * open.
 *
 * <p>Mirrors the
 * {@link com.kumouri.kmodigipresbe.module.chairfill.scoring.NoShowRiskScoringService} nightly cadence and
 * the {@code CoverageNudgeJob} per-tenant structure: a config-driven {@code @Scheduled} cron, the work
 * subscribed on {@link Schedulers#boundedElastic()} (never the Netty event loop), and the tenant scan
 * filtered to ACTIVE tenants whose {@code enabledModules} contains
 * {@link ChairFillAutoConfiguration#MODULE_KEY "chairfill"} — so a non-chairfill tenant (and every
 * non-salon tenant) is skipped. Wired as a {@code @Bean} in {@link ChairFillAutoConfiguration} (the
 * salon-spa hand-construction pattern), so it exists only when {@code kmosf.modules.chairfill.enabled=true}
 * and the salon-spa module it rides is loaded.
 *
 * <p><strong>Blast radius zero + best-effort (the rest-of-module posture):</strong> only chairfill tenants'
 * offers are ever read or written; a per-tenant or per-offer failure is caught and logged so one bad row
 * never aborts the rest; and a lost optimistic-lock race (a concurrent inbound YES just claimed the offer)
 * simply skips that row — an EXPIRED never clobbers a CLAIMED/SUPERSEDED.
 *
 * <p>The default cadence is nightly; deployments wanting a fresher ledger (e.g. a live waitlist board) can
 * tighten {@code kmosf.chairfill.gapfill.offer-expiry-cron} without code change — the claimability gate is
 * unaffected at any cadence.
 */
@Slf4j
public class WaitlistOfferExpiryService {

    private final TenantRepository tenantRepository;
    private final WaitlistOfferRepository offerRepository;

    public WaitlistOfferExpiryService(TenantRepository tenantRepository,
                                      WaitlistOfferRepository offerRepository) {
        this.tenantRepository = tenantRepository;
        this.offerRepository = offerRepository;
    }

    /**
     * Scheduled tick — config-driven cron, default nightly at 03:15 (offset from the
     * {@code NoShowRiskScoringService} 02:30 run and the retention purge at 03:00). Fire-and-forget
     * subscribe on {@link Schedulers#boundedElastic()} (never the Netty loop); the visible-for-test
     * {@link #sweepOnce()} returns the {@code Mono<Long>} an IT blocks.
     */
    @Scheduled(cron = "${kmosf.chairfill.gapfill.offer-expiry-cron:0 15 3 * * *}")
    public void scheduledSweep() {
        sweepOnce()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        expired -> {
                            if (expired > 0) {
                                log.info("ChairFill offer-expiry sweep flipped {} stale OFFERED -> EXPIRED",
                                        expired);
                            }
                        },
                        err -> log.error("ChairFill offer-expiry sweep failed", err));
    }

    /**
     * Visible-for-test entry — runs one full sweep across all ACTIVE chairfill tenants and returns the
     * total number of offers flipped to {@code EXPIRED}, so an IT can drive it deterministically (the
     * {@code CoverageNudgeJob.nudgeDueOnce()} posture). A per-tenant failure is logged and contributes 0.
     */
    public Mono<Long> sweepOnce() {
        Instant now = Instant.now();
        return tenantRepository.findAll()
                .filter(t -> t.getStatus() == Tenant.TenantStatus.ACTIVE
                        && t.getEnabledModules() != null
                        && t.getEnabledModules().contains(ChairFillAutoConfiguration.MODULE_KEY))
                .flatMap(tenant -> sweepTenant(tenant.getId(), now)
                        .onErrorResume(err -> {
                            log.warn("Offer-expiry sweep failed for tenant {}: {}",
                                    tenant.getId(), err.toString());
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum);
    }

    /**
     * Flip this tenant's past-{@code expiresAt} {@code OFFERED} offers to {@code EXPIRED}. The derived
     * finder carries the explicit {@code tenantId} (the {@code TenantScopedReactiveMongoRepository} marker
     * does not auto-scope derived finders), so no request context is needed — the
     * {@code NoShowRiskScoringService.runJobForTenant} precedent. Each flip is best-effort: a lost
     * optimistic-lock race (a concurrent claim just moved the row to CLAIMED/SUPERSEDED) is skipped, not
     * retried, so the sweep never overwrites a winner.
     */
    private Mono<Long> sweepTenant(UUID tenantId, Instant now) {
        return offerRepository
                .findByTenantIdAndStatusAndExpiresAtBefore(tenantId, WaitlistOffer.Status.OFFERED, now)
                .flatMap(offer -> offerRepository.save(offer.toBuilder()
                                .status(WaitlistOffer.Status.EXPIRED).build())
                        .thenReturn(1L)
                        .onErrorResume(err -> {
                            log.debug("Offer-expiry skip for offer {} (tenant {}): {}",
                                    offer.getId(), tenantId, err.toString());
                            return Mono.just(0L);
                        }))
                .reduce(0L, Long::sum);
    }
}
