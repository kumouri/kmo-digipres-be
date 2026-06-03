package com.kumouri.kmodigipresbe.integration.gbp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NMM GBP review-reply automation — GbpReviewReplyAdminIT: the admin approve/post surface. ADMIN JWT
 * (the {@code ProjectAssignmentCrudIT} pattern) + WireMock GBP for the reply {@code PUT} on
 * post. The admin controller is module-gated {@code matchIfMissing=true} (always-registerable) — it
 * does NOT require the default-OFF poller to be on.
 *
 * <h2>§7 no-live-external</h2>
 * The {@code POST /{id}/post} reaches Google via {@code GbpApiClient.postReply} against
 * {@code kmosf.gbp.api-base-url} pointed at WireMock ({@code @DynamicPropertySource}); the
 * {@code accessToken} is a sandbox fake. No live Google.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>GET lists only the DRAFTED rows (a POSTED row is excluded);</li>
 *   <li>POST /{id}/post (with an edited reply) → the edited text PUT to WireMock GBP → row POSTED;</li>
 *   <li>POST /{id}/post for a tenant with no google-business connection → 4030 (the reused client);</li>
 *   <li>POST /{id}/skip → row SKIPPED, no Google call;</li>
 *   <li>POST a non-existent id → 4032; a non-ADMIN caller → 1800.</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class GbpReviewReplyAdminIT {

    private static final String GBP_ACCESS_TOKEN = "ya29.gbp-test-access-token-fake";

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void gbpProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.gbp.api-base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired GbpReviewReplyRepository reviewReplies;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("gbp-admin-it-" + tenantId)
                .displayName("GBP Admin IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@gbp.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@gbp.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private void seedGbpConnection() {
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("google-business")
                .secrets(new HashMap<>(Map.of("accessToken", GBP_ACCESS_TOKEN)))
                .build()).block();
    }

    private GbpReviewReply seedDraft(String reviewId, GbpReviewReply.Status status) {
        return reviewReplies.save(GbpReviewReply.builder()
                .tenantId(tenantId)
                .reviewId(reviewId)
                .rating(5)
                .comment("Great service!")
                .reviewerName("Jane Doe")
                .draftedReply("Thank you, Jane! We appreciate it.")
                .status(status)
                .receivedAt(Instant.now())
                .build()).block();
    }

    private void stubPostReplyOk() {
        wireMock.stubFor(put(urlPathMatching("/v4/.*/reply"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"comment\":\"posted\"}")));
    }

    // -------------------------------------------------------------------------
    // GET lists only DRAFTED
    // -------------------------------------------------------------------------

    @Test
    void list_returnsOnlyDrafted() {
        seedDraft("reviews/d1", GbpReviewReply.Status.DRAFTED);
        seedDraft("reviews/d2", GbpReviewReply.Status.DRAFTED);
        seedDraft("reviews/posted", GbpReviewReply.Status.POSTED);

        List<GbpReviewReply> body = web.get().uri("/gbp/review-replies")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(GbpReviewReply.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body).hasSize(2);
        assertThat(body).extracting(GbpReviewReply::getReviewId)
                .containsExactlyInAnyOrder("reviews/d1", "reviews/d2");
    }

    @Test
    void list_nonAdmin_403_1800() {
        seedDraft("reviews/d1", GbpReviewReply.Status.DRAFTED);

        web.get().uri("/gbp/review-replies")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    // -------------------------------------------------------------------------
    // POST /{id}/post -> edited reply PUT to GBP -> POSTED
    // -------------------------------------------------------------------------

    @Test
    void post_editedReply_putsToGbp_andMarksPosted() {
        seedGbpConnection();
        stubPostReplyOk();
        GbpReviewReply draft = seedDraft("reviews/r1", GbpReviewReply.Status.DRAFTED);

        GbpReviewReply posted = web.post().uri("/gbp/review-replies/{id}/post", draft.getId())
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("reply", "Thanks so much, Jane — edited by Rob!"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(GbpReviewReply.class)
                .returnResult().getResponseBody();

        assertThat(posted).isNotNull();
        assertThat(posted.getStatus()).isEqualTo(GbpReviewReply.Status.POSTED);
        assertThat(posted.getPostedAt()).isNotNull();
        assertThat(posted.getDraftedReply()).contains("edited by Rob");

        // The edited reply was PUT to WireMock GBP (the reviewId path segment + the body).
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .putRequestedFor(urlPathMatching("/v4/.*/reply"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.comment",
                                com.github.tomakehurst.wiremock.client.WireMock.containing("edited by Rob"))));

        // The persisted row is POSTED.
        GbpReviewReply reloaded = reviewReplies.findByTenantIdAndId(tenantId, draft.getId()).block();
        assertThat(reloaded).isNotNull();
        assertThat(reloaded.getStatus()).isEqualTo(GbpReviewReply.Status.POSTED);
    }

    // -------------------------------------------------------------------------
    // POST /{id}/post with no google-business connection -> 4030
    // -------------------------------------------------------------------------

    @Test
    void post_notConnected_4030() {
        // No google-business connection seeded.
        GbpReviewReply draft = seedDraft("reviews/r1", GbpReviewReply.Status.DRAFTED);

        web.post().uri("/gbp/review-replies/{id}/post", draft.getId())
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4030);

        // The row was NOT marked POSTED (the gate failed before the status flip).
        GbpReviewReply reloaded = reviewReplies.findByTenantIdAndId(tenantId, draft.getId()).block();
        assertThat(reloaded.getStatus()).isEqualTo(GbpReviewReply.Status.DRAFTED);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // POST /{id}/skip -> SKIPPED, no Google call
    // -------------------------------------------------------------------------

    @Test
    void skip_marksSkipped_noGoogleCall() {
        GbpReviewReply draft = seedDraft("reviews/r1", GbpReviewReply.Status.DRAFTED);

        GbpReviewReply skipped = web.post().uri("/gbp/review-replies/{id}/skip", draft.getId())
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(GbpReviewReply.class)
                .returnResult().getResponseBody();

        assertThat(skipped).isNotNull();
        assertThat(skipped.getStatus()).isEqualTo(GbpReviewReply.Status.SKIPPED);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    // -------------------------------------------------------------------------
    // POST /{id}/post for a non-existent id -> 4032
    // -------------------------------------------------------------------------

    @Test
    void post_notFound_4032() {
        web.post().uri("/gbp/review-replies/{id}/post", UUID.randomUUID())
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4032);
    }
}
