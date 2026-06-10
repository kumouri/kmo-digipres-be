package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.controller.dto.WaitlistBoardDTO;
import com.kumouri.kmodigipresbe.module.chairfill.controller.dto.WaitlistBoardEntryDTO;
import com.kumouri.kmodigipresbe.module.chairfill.controller.dto.WaitlistOfferDTO;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChairFill CF-5a — WaitlistBoardIT: the staff-facing waitlist-board read backing the CF-5 board FE.
 * Drives {@code GET /chairfill/waitlist/board|entries|offers} over HTTP ({@code WebTestClient}, the
 * {@code SalonReviewReplyIT} JWT-auth pattern), seeding {@link WaitlistEntry}/{@link WaitlistOffer}
 * rows directly via Mongo (the {@code GapFillWaitlistIT} seeding posture). A pure read — no WireMock,
 * no external (§7).
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>{@code board} returns OPEN entries + recent offers with the correct projection, OPEN-only
 *       filtering (a FULFILLED entry is excluded), newest-first ordering, and offer status surfaced;</li>
 *   <li>{@code entries} returns OPEN entries newest-first, FULFILLED/CANCELLED excluded;</li>
 *   <li>{@code offers} returns recent offers (all statuses) newest-sent-first, capped by {@code limit};</li>
 *   <li>a non-chairfill tenant → 1132 module-gate not-enabled (the {@code 4202}/{@code 2700} posture);</li>
 *   <li>a non-staff role → 1800 forbidden;</li>
 *   <li>tenant isolation — another tenant's rows never leak.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true"
})
class WaitlistBoardIT {

    private static final String MENU_ITEM_ID = "balayage";

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID stylistId;
    private String staffToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), WaitlistEntry.class).block();
        mongo.remove(new Query(), WaitlistOffer.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        stylistId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@cf5a.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), WaitlistEntry.class).block();
        mongo.remove(new Query(), WaitlistOffer.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // ── 1. board: OPEN entries + recent offers, projection / OPEN-filter / ordering / status ──

    @Test
    void board_returnsOpenEntriesAndRecentOffers_projectedOrderedFiltered() {
        // Two OPEN entries (older + newer) and a FULFILLED one that must NOT appear on the board.
        UUID dana = UUID.randomUUID();
        UUID flo = UUID.randomUUID();
        UUID pat = UUID.randomUUID();
        seedEntry(tenantId, dana, MENU_ITEM_ID, WaitlistEntry.Status.OPEN,
                Instant.now().minus(Duration.ofMinutes(30)));
        seedEntry(tenantId, flo, MENU_ITEM_ID, WaitlistEntry.Status.OPEN,
                Instant.now().minus(Duration.ofMinutes(5)));
        seedEntry(tenantId, pat, MENU_ITEM_ID, WaitlistEntry.Status.FULFILLED,
                Instant.now().minus(Duration.ofMinutes(10)));

        // Two offers with different statuses + sent times.
        UUID freed = UUID.randomUUID();
        seedOffer(tenantId, freed, dana, "+16185550150", 0, WaitlistOffer.Status.CLAIMED,
                Instant.now().minus(Duration.ofMinutes(20)));
        seedOffer(tenantId, freed, flo, "+16185550151", 1, WaitlistOffer.Status.SUPERSEDED,
                Instant.now().minus(Duration.ofMinutes(2)));

        WaitlistBoardDTO board = web.get().uri("/chairfill/waitlist/board")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(WaitlistBoardDTO.class).returnResult().getResponseBody();

        assertThat(board).isNotNull();

        // OPEN entries only (FULFILLED excluded), newest join first.
        assertThat(board.openEntries()).extracting(WaitlistBoardEntryDTO::contactId)
                .containsExactly(flo, dana);
        WaitlistBoardEntryDTO first = board.openEntries().get(0);
        assertThat(first.serviceMenuItemId()).isEqualTo(MENU_ITEM_ID);
        assertThat(first.smsOptIn()).isTrue();
        assertThat(first.createdAt()).isNotNull();

        // Recent offers, newest sent first; status + denormalized fields projected.
        assertThat(board.recentOffers()).extracting(WaitlistOfferDTO::contactId)
                .containsExactly(flo, dana);
        WaitlistOfferDTO topOffer = board.recentOffers().get(0);
        assertThat(topOffer.status()).isEqualTo(WaitlistOffer.Status.SUPERSEDED);
        assertThat(topOffer.serviceMenuItemName()).isEqualTo("Balayage");
        assertThat(topOffer.contactPhone()).isEqualTo("+16185550151");
        assertThat(topOffer.staffMemberId()).isEqualTo(stylistId);
        assertThat(topOffer.freedBookingId()).isEqualTo(freed);
        assertThat(board.recentOffers().get(1).status()).isEqualTo(WaitlistOffer.Status.CLAIMED);
    }

    // ── 2. entries: OPEN only, newest first ───────────────────────────────────

    @Test
    void entries_returnsOpenOnly_newestFirst() {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        seedEntry(tenantId, a, MENU_ITEM_ID, WaitlistEntry.Status.OPEN,
                Instant.now().minus(Duration.ofMinutes(40)));
        seedEntry(tenantId, b, MENU_ITEM_ID, WaitlistEntry.Status.OPEN,
                Instant.now().minus(Duration.ofMinutes(1)));
        seedEntry(tenantId, UUID.randomUUID(), MENU_ITEM_ID, WaitlistEntry.Status.CANCELLED,
                Instant.now());

        List<WaitlistBoardEntryDTO> entries = web.get().uri("/chairfill/waitlist/entries")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(WaitlistBoardEntryDTO.class).returnResult().getResponseBody();

        assertThat(entries).isNotNull();
        assertThat(entries).extracting(WaitlistBoardEntryDTO::contactId).containsExactly(b, a);
    }

    // ── 3. offers: all statuses, newest sent first, capped by limit ───────────

    @Test
    void offers_returnsAllStatuses_newestFirst_cappedByLimit() {
        UUID freed = UUID.randomUUID();
        // 3 offers at distinct sent times.
        seedOffer(tenantId, freed, UUID.randomUUID(), "+16185550150", 0, WaitlistOffer.Status.EXPIRED,
                Instant.now().minus(Duration.ofMinutes(30)));
        seedOffer(tenantId, freed, UUID.randomUUID(), "+16185550151", 1, WaitlistOffer.Status.OFFERED,
                Instant.now().minus(Duration.ofMinutes(20)));
        seedOffer(tenantId, freed, UUID.randomUUID(), "+16185550152", 2, WaitlistOffer.Status.CLAIMED,
                Instant.now().minus(Duration.ofMinutes(10)));

        // Default (no limit) → all 3, newest first.
        List<WaitlistOfferDTO> all = web.get().uri("/chairfill/waitlist/offers")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(WaitlistOfferDTO.class).returnResult().getResponseBody();
        assertThat(all).isNotNull();
        assertThat(all).extracting(WaitlistOfferDTO::status).containsExactly(
                WaitlistOffer.Status.CLAIMED, WaitlistOffer.Status.OFFERED, WaitlistOffer.Status.EXPIRED);

        // limit=1 → only the newest.
        List<WaitlistOfferDTO> capped = web.get().uri("/chairfill/waitlist/offers?limit=1")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(WaitlistOfferDTO.class).returnResult().getResponseBody();
        assertThat(capped).hasSize(1);
        assertThat(capped.get(0).status()).isEqualTo(WaitlistOffer.Status.CLAIMED);
    }

    // ── 4. non-chairfill tenant → 1132 module-gate not-enabled ────────────────

    @Test
    void nonChairfillTenant_board_isModuleGated_1132() {
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of("salon-spa")); // drop chairfill
        tenants.save(t).block();

        web.get().uri("/chairfill/waitlist/board")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    // ── 5. non-staff role → 1800 forbidden ────────────────────────────────────

    @Test
    void nonStaff_board_isForbidden() {
        // BE-02 StaffAuthorizationWebFilter rejects a non-STAFF principal at the central
        // baseline (1803) before the controller RoleGuard (1800) is reached; still 403.
        User noRole = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("norole@cf5a.test")
                .roles(Set.of()).status(User.UserStatus.ACTIVE).build();
        users.save(noRole).block();
        String noRoleToken = "Bearer " + jwt.mint(noRole);

        web.get().uri("/chairfill/waitlist/board")
                .header("Authorization", noRoleToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1803);
    }

    // ── 6. tenant isolation — another tenant's rows never leak ────────────────

    @Test
    void board_isTenantIsolated() {
        // This tenant: one OPEN entry + one offer.
        UUID mine = UUID.randomUUID();
        seedEntry(tenantId, mine, MENU_ITEM_ID, WaitlistEntry.Status.OPEN, Instant.now());
        seedOffer(tenantId, UUID.randomUUID(), mine, "+16185550150", 0, WaitlistOffer.Status.OFFERED,
                Instant.now());

        // A different chairfill tenant with its own entry + offer.
        UUID other = UUID.randomUUID();
        seedTenant(other, Set.of("salon-spa", "chairfill"));
        seedEntry(other, UUID.randomUUID(), MENU_ITEM_ID, WaitlistEntry.Status.OPEN, Instant.now());
        seedOffer(other, UUID.randomUUID(), UUID.randomUUID(), "+16185559999", 0,
                WaitlistOffer.Status.OFFERED, Instant.now());

        WaitlistBoardDTO board = web.get().uri("/chairfill/waitlist/board")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(WaitlistBoardDTO.class).returnResult().getResponseBody();

        assertThat(board).isNotNull();
        assertThat(board.openEntries()).extracting(WaitlistBoardEntryDTO::contactId).containsExactly(mine);
        assertThat(board.recentOffers()).hasSize(1);
        assertThat(board.recentOffers().get(0).contactId()).isEqualTo(mine);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("cf5a-it-" + tid)
                .displayName("ChairFill CF-5a IT Salon").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    private void seedEntry(UUID tid, UUID contactId, String serviceItemId,
                           WaitlistEntry.Status status, Instant createdAt) {
        // @CreatedDate stamps createdAt on the initial insert; save again (now an update — known id +
        // non-null version) to overwrite it with the explicit ordering value (the RetentionPurgeIT
        // back-dating precedent), so the newest-first ordering assertions are deterministic.
        WaitlistEntry saved = mongo.save(WaitlistEntry.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .contactId(contactId)
                .serviceMenuItemId(serviceItemId)
                .preferredStaffMemberId(stylistId)
                .smsOptIn(true)
                .status(status)
                .build()).block();
        mongo.save(saved.toBuilder().createdAt(createdAt).build()).block();
    }

    private void seedOffer(UUID tid, UUID freedBookingId, UUID contactId, String phone, int rank,
                           WaitlistOffer.Status status, Instant sentAt) {
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        mongo.save(WaitlistOffer.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .freedBookingId(freedBookingId)
                .waitlistEntryId(UUID.randomUUID())
                .contactId(contactId)
                .contactPhone(phone)
                .staffMemberId(stylistId)
                .serviceMenuItemId(MENU_ITEM_ID)
                .serviceMenuItemName("Balayage")
                .slotStart(slotStart)
                .slotEnd(slotStart.plus(Duration.ofMinutes(120)))
                .rank(rank)
                .status(status)
                .sentAt(sentAt)
                .expiresAt(sentAt.plus(Duration.ofMinutes(10)))
                .build()).block();
    }
}
