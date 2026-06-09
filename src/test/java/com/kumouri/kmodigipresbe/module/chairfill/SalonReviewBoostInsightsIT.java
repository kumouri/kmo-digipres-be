package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.gbp.ReviewRequestService;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.integration.SentimentSource;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.reviewboost.SalonReviewBoardDTO;
import com.kumouri.kmodigipresbe.module.chairfill.reviewboost.StylistReviewStatsDTO;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.awaitility.Awaitility;
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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T6 Salon "ReviewBoost" — SalonReviewBoostInsightsIT: the per-stylist review board read
 * ({@code GET /api/v1/chairfill/reviewboost/insights}) backing the salon dashboard. ADMIN JWT (the
 * {@code WaitlistBoardIT} / {@code GbpReviewReplyAdminIT} pattern). A pure read aggregation over the
 * shipped E3 review engine — no WireMock, no external (§7).
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li><strong>End-to-end per-stylist attribution (the headline)</strong> — drive the <em>real</em>
 *       {@link SalonBookingService#complete} on a CONFIRMED booking that has a {@code staffMemberId}; the
 *       shipped {@code ReviewRequestService} subscriber must create a PENDING request attributed
 *       {@code (STAFF, staffMemberId)}. Proves the salon attribution works through the unchanged engine
 *       (no T6 change to the create path).</li>
 *   <li><strong>The board aggregation</strong> — two stylists with uneven SENT request funnels + a tenant
 *       review header; the board returns one row per active stylist with the right funnel, plus the
 *       tenant-wide review-content + rollup. Requests are driven deterministically via
 *       {@link ReviewRequestService#handle} (the {@code ReviewRequestCreationIT} precedent).</li>
 *   <li>tenant isolation — a second salon's stylists/requests never leak;</li>
 *   <li>non-ADMIN → 1800; a non-chairfill tenant → 1132 module-gate.</li>
 * </ol>
 *
 * <p>Shard-safe: no mocks, no WireMock, self-clean {@code mongo.remove}; the two module flags are the only
 * {@code @TestPropertySource} additions (the {@code WaitlistBoardIT} posture).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        // A short request-delay so a driven complete() yields a request immediately (dueAt in the past
        // is irrelevant here — the board counts SENT, and creation is what we assert end-to-end).
        "kmosf.review-engine.request-delay=PT0S"
})
class SalonReviewBoostInsightsIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired StaffMemberRepository staffMembers;
    @Autowired BookingRepository bookings;
    @Autowired ReviewRequestRepository reviewRequests;
    @Autowired GbpReviewReplyRepository reviewReplies;
    @Autowired SalonBookingService salonBookingService;
    @Autowired ReviewRequestService reviewRequestService;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;
    private final UUID stylistMaya = UUID.randomUUID();
    private final UUID stylistJordan = UUID.randomUUID();

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@rb.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@rb.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        staffMembers.save(stylist(tenantId, stylistMaya, "Maya")).block();
        staffMembers.save(stylist(tenantId, stylistJordan, "Jordan")).block();
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        mongo.remove(new Query(), Booking.class).block();
        mongo.remove(new Query(), StaffMember.class).block();
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // ── 1. End-to-end: the REAL SalonBookingService.complete() -> STAFF-attributed request ──

    @Test
    void completingABooking_createsStylistAttributedReviewRequest_viaTheShippedEngine() {
        UUID contactId = UUID.randomUUID();
        // A CONFIRMED booking with a stylist — saved directly in CONFIRMED so complete() is legal.
        UUID bookingId = UUID.randomUUID();
        saveAsTenant(Booking.builder()
                .id(bookingId).tenantId(tenantId).contactId(contactId).staffMemberId(stylistMaya)
                .serviceMenuItemId("cut").serviceMenuItemName("Cut")
                .scheduledStart(Instant.now().minus(2, ChronoUnit.HOURS))
                .scheduledEnd(Instant.now().minus(1, ChronoUnit.HOURS))
                .status(BookingStatus.CONFIRMED)
                .build());

        // Drive the REAL complete() — it emits BOOKING_COMPLETED{staffMemberId}; the shipped
        // ReviewRequestService subscriber (un-changed by T6) creates the STAFF-attributed request.
        salonBookingService.complete(bookingId)
                .contextWrite(TenantContextHolder.write(staffCtx()))
                .block();

        Awaitility.await().atMost(15, TimeUnit.SECONDS).untilAsserted(() -> {
            List<ReviewRequest> rows = mongo.findAll(ReviewRequest.class).collectList().block();
            assertThat(rows).hasSize(1);
            ReviewRequest r = rows.get(0);
            assertThat(r.getSubjectType()).isEqualTo(ReviewSubjectType.STAFF);
            assertThat(r.getSubjectId()).isEqualTo(stylistMaya);
            assertThat(r.getContactId()).isEqualTo(contactId);
            assertThat(r.getSourceEventType()).isEqualTo(DomainEventType.BOOKING_COMPLETED);
        });
    }

    // ── 2. The board: per-stylist funnels + tenant review header ──

    @Test
    void board_returnsPerStylistFunnels_andTenantReviewHeader() {
        // Reviews: 5/5/2 -> avg 4.00, POSITIVE/POSITIVE/NEGATIVE; one unclassified.
        saveReview(tenantId, "rb/r1", 5, ReviewSentiment.POSITIVE);
        saveReview(tenantId, "rb/r2", 5, ReviewSentiment.POSITIVE);
        saveReview(tenantId, "rb/r3", 2, ReviewSentiment.NEGATIVE);
        saveReview(tenantId, "rb/r4", null, null);

        // Maya: 2 SENT + 1 PENDING; Jordan: 1 SENT. (Driven via the engine's visible-for-test handle.)
        sentRequest(stylistMaya, UUID.randomUUID());
        sentRequest(stylistMaya, UUID.randomUUID());
        pendingRequest(stylistMaya, UUID.randomUUID());
        sentRequest(stylistJordan, UUID.randomUUID());

        SalonReviewBoardDTO board = web.get().uri("/chairfill/reviewboost/insights")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBody(SalonReviewBoardDTO.class).returnResult().getResponseBody();

        assertThat(board).isNotNull();
        // Tenant review header.
        assertThat(board.reviewCount()).isEqualTo(4);
        assertThat(board.averageRating()).isEqualTo(4.00);
        assertThat(board.positiveCount()).isEqualTo(2);
        assertThat(board.negativeCount()).isEqualTo(1);
        assertThat(board.unclassifiedCount()).isEqualTo(1);
        // Tenant funnel rollup: 3 SENT.
        assertThat(board.totalRequestsSent()).isEqualTo(3);

        // Per-stylist rows — one per active stylist.
        assertThat(board.stylists()).hasSize(2);
        StylistReviewStatsDTO maya = stylistRow(board, stylistMaya);
        StylistReviewStatsDTO jordan = stylistRow(board, stylistJordan);
        assertThat(maya.displayName()).isEqualTo("Maya");
        assertThat(maya.requestsSent()).isEqualTo(2);   // the PENDING one is excluded
        assertThat(jordan.requestsSent()).isEqualTo(1);
        // responded is the bounded proxy min(reviewCount, sent); responseRate scale-2.
        assertThat(maya.requestsResponded()).isEqualTo(2);
        assertThat(maya.responseRate()).isEqualTo(1.00);
    }

    // ── 3. tenant isolation ──

    @Test
    void board_isTenantIsolated() {
        sentRequest(stylistMaya, UUID.randomUUID());
        saveReview(tenantId, "rb/mine", 5, ReviewSentiment.POSITIVE);

        // A second salon with its own stylist + SENT request + review — must not leak.
        UUID other = UUID.randomUUID();
        seedTenant(other, Set.of("salon-spa", "chairfill"));
        UUID otherStylist = UUID.randomUUID();
        staffMembers.save(stylist(other, otherStylist, "Otherist")).block();
        reviewRequests.save(ReviewRequest.builder().id(UUID.randomUUID()).tenantId(other)
                .subjectType(ReviewSubjectType.STAFF).subjectId(otherStylist).contactId(UUID.randomUUID())
                .sourceEventType("booking.completed").status(ReviewRequest.Status.SENT)
                .dueAt(Instant.now()).sentAt(Instant.now()).build()).block();
        saveReview(other, "rb/other", 1, ReviewSentiment.NEGATIVE);

        SalonReviewBoardDTO board = web.get().uri("/chairfill/reviewboost/insights")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBody(SalonReviewBoardDTO.class).returnResult().getResponseBody();

        assertThat(board).isNotNull();
        assertThat(board.reviewCount()).isEqualTo(1);             // only this tenant's review
        assertThat(board.stylists()).extracting(StylistReviewStatsDTO::staffMemberId)
                .containsExactlyInAnyOrder(stylistMaya, stylistJordan);  // not the other salon's stylist
        assertThat(board.totalRequestsSent()).isEqualTo(1);
    }

    // ── 4. non-ADMIN -> 1800; non-chairfill tenant -> 1132 ──

    @Test
    void nonAdmin_isForbidden_1800() {
        web.get().uri("/chairfill/reviewboost/insights")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void nonChairfillTenant_isModuleGated_1132() {
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of("salon-spa")); // drop chairfill
        tenants.save(t).block();

        web.get().uri("/chairfill/reviewboost/insights")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    // ── helpers ──

    private TenantContext staffCtx() {
        return new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
    }

    private void saveAsTenant(Booking booking) {
        mongo.save(booking).contextWrite(TenantContextHolder.write(staffCtx())).block();
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("rb-it-" + tid)
                .displayName("ReviewBoost IT Salon").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new java.math.BigDecimal("5.00"))
                .build()).block();
    }

    private StaffMember stylist(UUID tid, UUID id, String name) {
        return StaffMember.builder().id(id).tenantId(tid).displayName(name).active(true).build();
    }

    private void sentRequest(UUID stylistId, UUID contactId) {
        reviewRequests.save(ReviewRequest.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .subjectType(ReviewSubjectType.STAFF).subjectId(stylistId).contactId(contactId)
                .sourceEventType("booking.completed").status(ReviewRequest.Status.SENT)
                .dueAt(Instant.now()).sentAt(Instant.now()).build()).block();
    }

    private void pendingRequest(UUID stylistId, UUID contactId) {
        reviewRequests.save(ReviewRequest.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .subjectType(ReviewSubjectType.STAFF).subjectId(stylistId).contactId(contactId)
                .sourceEventType("booking.completed").status(ReviewRequest.Status.PENDING)
                .dueAt(Instant.now()).build()).block();
    }

    private void saveReview(UUID tid, String reviewId, Integer rating, ReviewSentiment sentiment) {
        reviewReplies.save(GbpReviewReply.builder().id(UUID.randomUUID()).tenantId(tid)
                .reviewId(reviewId).rating(rating).comment(rating == null ? null : "A review")
                .sentiment(sentiment).sentimentSource(sentiment == null ? null : SentimentSource.RATING)
                .status(GbpReviewReply.Status.DRAFTED).receivedAt(Instant.now()).build()).block();
    }

    private static StylistReviewStatsDTO stylistRow(SalonReviewBoardDTO board, UUID staffMemberId) {
        return board.stylists().stream()
                .filter(s -> staffMemberId.equals(s.staffMemberId()))
                .findFirst().orElseThrow();
    }
}
