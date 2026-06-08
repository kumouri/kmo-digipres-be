package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.gapfill.WaitlistOfferExpiryService;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChairFill CF-3 follow-up — {@link WaitlistOfferExpiryService} sweep coverage. Asserts the sweeper flips a
 * past-{@code expiresAt} {@code OFFERED} offer to {@code EXPIRED} while leaving a still-valid {@code OFFERED}
 * offer untouched (the core requirement), that it never touches an already-terminal offer (the
 * {@code status=OFFERED} filter), and that a non-chairfill tenant's offers are never swept
 * (blast-radius-zero). Mirrors the {@code GapFillWaitlistIT} seeding helpers + the {@code NoShowRiskScoringIT}
 * plain-{@code @SpringBootTest} / Testcontainers-Mongo posture; the visible-for-test {@code sweepOnce()} is
 * driven directly ({@code block()}), no cron wait.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true"
})
class OfferExpirySweepIT {

    private static final String MENU_ITEM_ID = "balayage";

    @Autowired WaitlistOfferExpiryService expiryService;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID stylistId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), WaitlistOffer.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        tenantId = UUID.randomUUID();
        stylistId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
    }

    @Test
    void sweep_flipsPastExpiryOffered_toExpired_andLeavesValidOffered() {
        // A stale OFFERED offer — expiresAt 1 min in the PAST → should flip to EXPIRED.
        UUID staleId = seedOffer(tenantId, WaitlistOffer.Status.OFFERED,
                Instant.now().minus(Duration.ofMinutes(1)));
        // A still-valid OFFERED offer — expiresAt 10 min in the FUTURE → should stay OFFERED.
        UUID validId = seedOffer(tenantId, WaitlistOffer.Status.OFFERED,
                Instant.now().plus(Duration.ofMinutes(10)));

        Long flipped = expiryService.sweepOnce().block();

        assertThat(flipped).isEqualTo(1L);
        assertThat(mongo.findById(staleId, WaitlistOffer.class).block().getStatus())
                .isEqualTo(WaitlistOffer.Status.EXPIRED);
        assertThat(mongo.findById(validId, WaitlistOffer.class).block().getStatus())
                .isEqualTo(WaitlistOffer.Status.OFFERED);
    }

    @Test
    void sweep_leavesAlreadyTerminalOffers_untouched() {
        // A CLAIMED offer that happens to be past-expiry — the sweep filters on status=OFFERED, so it
        // must NOT be re-flipped to EXPIRED (never clobber a winner's CLAIMED).
        UUID claimedId = seedOffer(tenantId, WaitlistOffer.Status.CLAIMED,
                Instant.now().minus(Duration.ofMinutes(5)));
        UUID supersededId = seedOffer(tenantId, WaitlistOffer.Status.SUPERSEDED,
                Instant.now().minus(Duration.ofMinutes(5)));

        Long flipped = expiryService.sweepOnce().block();

        assertThat(flipped).isEqualTo(0L);
        assertThat(mongo.findById(claimedId, WaitlistOffer.class).block().getStatus())
                .isEqualTo(WaitlistOffer.Status.CLAIMED);
        assertThat(mongo.findById(supersededId, WaitlistOffer.class).block().getStatus())
                .isEqualTo(WaitlistOffer.Status.SUPERSEDED);
    }

    @Test
    void sweep_nonChairfillTenant_isHardNoOp() {
        // A salon tenant WITHOUT chairfill — its past-expiry OFFERED offer must never be swept.
        UUID other = UUID.randomUUID();
        seedTenant(other, Set.of("salon-spa"));
        UUID otherOffer = seedOffer(other, WaitlistOffer.Status.OFFERED,
                Instant.now().minus(Duration.ofMinutes(1)));

        Long flipped = expiryService.sweepOnce().block();

        assertThat(flipped).isEqualTo(0L);
        assertThat(mongo.findById(otherOffer, WaitlistOffer.class).block().getStatus())
                .isEqualTo(WaitlistOffer.Status.OFFERED);
    }

    // ── helpers (mirror GapFillWaitlistIT) ──────────────────────────────────────

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("cf3-expiry-" + tid)
                .displayName("ChairFill CF-3 Expiry IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    private UUID seedOffer(UUID tid, WaitlistOffer.Status status, Instant expiresAt) {
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        return mongo.save(WaitlistOffer.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .freedBookingId(UUID.randomUUID())
                .contactId(UUID.randomUUID())
                .contactPhone("+16185550150")
                .staffMemberId(stylistId)
                .serviceMenuItemId(MENU_ITEM_ID)
                .serviceMenuItemName("Balayage")
                .slotStart(slotStart)
                .slotEnd(slotStart.plus(Duration.ofMinutes(120)))
                .rank(0)
                .status(status)
                .sentAt(Instant.now())
                .expiresAt(expiresAt)
                .build()).block().getId();
    }
}
