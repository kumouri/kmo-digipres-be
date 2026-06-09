package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.integration.SentimentSource;
import com.kumouri.kmodigipresbe.model.response.ReviewInsights;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
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

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3 Review Engine — ReviewInsightsIT: the ADMIN per-entity insights read API
 * ({@code GET /gbp/review-insights[/{subjectType}/{subjectId}]}). ADMIN JWT (the
 * {@code GbpReviewReplyAdminIT} pattern); pure read aggregation (no WireMock / no external).
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>tenant rollup — review-content (count, avg rating, sentiment breakdown) over all reviews +
 *       the request funnel over all SENT requests;</li>
 *   <li>per-subject rollup — the request funnel scoped to {@code (STAFF, stylist)} (only that stylist's
 *       SENT requests count) while the review-content block is the tenant-wide health;</li>
 *   <li>cross-tenant isolation — a second tenant's reviews/requests never bleed in;</li>
 *   <li>invalid subjectType path segment → {@code 4340}; a non-ADMIN caller → {@code 1800}.</li>
 * </ul>
 *
 * <p>Shard-safe: no mocks, no WireMock, self-clean {@code mongo.remove}; no
 * {@code application-test.properties} / {@code build.gradle} shard change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ReviewInsightsIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired GbpReviewReplyRepository reviewReplies;
    @Autowired ReviewRequestRepository reviewRequests;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;

    private final UUID stylistA = UUID.randomUUID();
    private final UUID stylistB = UUID.randomUUID();

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("review-insights-it-" + tenantId)
                .displayName("Review Insights IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@ri.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@ri.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        // Reviews for THIS tenant: ratings 5/5/2 → avg 4.00; sentiments POSITIVE/POSITIVE/NEGATIVE
        // + one with null sentiment (unclassified).
        saveReview(tenantId, "reviews/r1", 5, ReviewSentiment.POSITIVE);
        saveReview(tenantId, "reviews/r2", 5, ReviewSentiment.POSITIVE);
        saveReview(tenantId, "reviews/r3", 2, ReviewSentiment.NEGATIVE);
        saveReview(tenantId, "reviews/r4", null, null); // unclassified + no rating

        // Requests: stylistA has 2 SENT + 1 PENDING; stylistB has 1 SENT. Tenant SENT = 3.
        saveRequest(tenantId, ReviewSubjectType.STAFF, stylistA, ReviewRequest.Status.SENT);
        saveRequest(tenantId, ReviewSubjectType.STAFF, stylistA, ReviewRequest.Status.SENT);
        saveRequest(tenantId, ReviewSubjectType.STAFF, stylistA, ReviewRequest.Status.PENDING);
        saveRequest(tenantId, ReviewSubjectType.STAFF, stylistB, ReviewRequest.Status.SENT);

        // A second tenant with its own reviews + requests — must never bleed into this tenant's rollup.
        UUID other = UUID.randomUUID();
        tenants.save(Tenant.builder().id(other).slug("ri-other-" + other)
                .displayName("Other").status(Tenant.TenantStatus.ACTIVE).build()).block();
        saveReview(other, "reviews/x1", 1, ReviewSentiment.NEGATIVE);
        saveRequest(other, ReviewSubjectType.STAFF, stylistA, ReviewRequest.Status.SENT);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private void saveReview(UUID tid, String reviewId, Integer rating, ReviewSentiment sentiment) {
        reviewReplies.save(GbpReviewReply.builder()
                .tenantId(tid)
                .reviewId(reviewId)
                .rating(rating)
                .comment(rating == null ? null : "A review")
                .sentiment(sentiment)
                .sentimentSource(sentiment == null ? null : SentimentSource.RATING)
                .status(GbpReviewReply.Status.DRAFTED)
                .receivedAt(Instant.now())
                .build()).block();
    }

    private void saveRequest(UUID tid, ReviewSubjectType type, UUID subjectId, ReviewRequest.Status status) {
        reviewRequests.save(ReviewRequest.builder()
                .tenantId(tid)
                .subjectType(type)
                .subjectId(subjectId)
                .contactId(UUID.randomUUID())
                .sourceEventType("booking.completed")
                .status(status)
                .dueAt(Instant.now())
                .sentAt(status == ReviewRequest.Status.SENT ? Instant.now() : null)
                .build()).block();
    }

    // -------------------------------------------------------------------------
    // Tenant rollup
    // -------------------------------------------------------------------------

    @Test
    void tenantRollup_aggregatesReviewContentAndRequestFunnel() {
        ReviewInsights insights = web.get().uri("/gbp/review-insights")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReviewInsights.class)
                .returnResult().getResponseBody();

        assertThat(insights).isNotNull();
        assertThat(insights.subjectType()).isNull();
        assertThat(insights.subjectId()).isNull();
        // 4 reviews; avg over the 3 rated (5,5,2)=12/3=4.00.
        assertThat(insights.reviewCount()).isEqualTo(4);
        assertThat(insights.averageRating()).isEqualTo(4.00);
        assertThat(insights.positiveCount()).isEqualTo(2);
        assertThat(insights.neutralCount()).isEqualTo(0);
        assertThat(insights.negativeCount()).isEqualTo(1);
        assertThat(insights.unclassifiedCount()).isEqualTo(1);
        // Tenant SENT requests = 3 (stylistA 2 + stylistB 1); the PENDING one does not count.
        assertThat(insights.requestsSent()).isEqualTo(3);
        // responded = min(reviewCount=4, sent=3) = 3 → responseRate = 1.00.
        assertThat(insights.requestsResponded()).isEqualTo(3);
        assertThat(insights.responseRate()).isEqualTo(1.00);
    }

    // -------------------------------------------------------------------------
    // Per-subject rollup — request funnel scoped to the stylist
    // -------------------------------------------------------------------------

    @Test
    void subjectRollup_scopesRequestFunnelToTheStylist() {
        ReviewInsights insights = web.get()
                .uri("/gbp/review-insights/{type}/{id}", "STAFF", stylistA)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReviewInsights.class)
                .returnResult().getResponseBody();

        assertThat(insights).isNotNull();
        assertThat(insights.subjectType()).isEqualTo(ReviewSubjectType.STAFF);
        assertThat(insights.subjectId()).isEqualTo(stylistA);
        // stylistA has exactly 2 SENT requests (the PENDING one is excluded; stylistB's is not theirs).
        assertThat(insights.requestsSent()).isEqualTo(2);
        // The review-content block is the tenant-wide health (GBP reviews are not subject-attributed).
        assertThat(insights.reviewCount()).isEqualTo(4);
        assertThat(insights.averageRating()).isEqualTo(4.00);
        // responded = min(4, 2) = 2 → responseRate = 1.00.
        assertThat(insights.requestsResponded()).isEqualTo(2);
        assertThat(insights.responseRate()).isEqualTo(1.00);
    }

    @Test
    void subjectRollup_caseInsensitiveType_andEmptySubject_zeros() {
        // stylistB: 1 SENT (lowercase type segment must parse).
        ReviewInsights b = web.get()
                .uri("/gbp/review-insights/{type}/{id}", "staff", stylistB)
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReviewInsights.class)
                .returnResult().getResponseBody();
        assertThat(b).isNotNull();
        assertThat(b.requestsSent()).isEqualTo(1);

        // A PROJECT subject with no requests → request funnel zeros (review-content still tenant-wide).
        ReviewInsights none = web.get()
                .uri("/gbp/review-insights/{type}/{id}", "PROJECT", UUID.randomUUID())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ReviewInsights.class)
                .returnResult().getResponseBody();
        assertThat(none).isNotNull();
        assertThat(none.requestsSent()).isEqualTo(0);
        assertThat(none.requestsResponded()).isEqualTo(0);
        assertThat(none.responseRate()).isEqualTo(0.0);
    }

    // -------------------------------------------------------------------------
    // Invalid subjectType -> 4340; non-admin -> 1800
    // -------------------------------------------------------------------------

    @Test
    void invalidSubjectType_4340() {
        web.get().uri("/gbp/review-insights/{type}/{id}", "BOGUS", UUID.randomUUID())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4340);
    }

    @Test
    void nonAdmin_403_1800() {
        web.get().uri("/gbp/review-insights")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }
}
