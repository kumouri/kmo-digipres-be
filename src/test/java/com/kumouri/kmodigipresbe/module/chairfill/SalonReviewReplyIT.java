package com.kumouri.kmodigipresbe.module.chairfill;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.gbp.GbpReplyDraftService;
import com.kumouri.kmodigipresbe.integration.gbp.GbpReview;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * ChairFill CF-4 — SalonReviewReplyIT: the salon-generalized AI review-reply (D4). Drives the
 * paste-in surface ({@code POST /chairfill/reviews/draft}) + the <strong>reused</strong> GBP admin
 * approve/skip queue ({@code GET /gbp/review-replies}, {@code POST /{id}/post}|{@code /{id}/skip})
 * over HTTP ({@code WebTestClient}, the {@code GbpReviewReplyAdminIT} + {@code GapFillWaitlistIT}
 * pattern). Anthropic → WireMock via {@code kmosf.ai.anthropic.base-url}; the GBP reply PUT (on
 * approve→post) → the same WireMock via {@code kmosf.gbp.api-base-url}. No live external (§7).
 *
 * <h2>Coverage (plan CF-4 ITs)</h2>
 * <ol>
 *   <li>a salon paste-in review → a DRAFTED reply in the queue, and the Claude request carried (a)
 *       the salon brand-tone system prompt and (b) an exemplar past approved reply (RAG, D4) — via
 *       {@code matchingJsonPath};</li>
 *   <li>approve (post) → POSTED + leaves the DRAFTED queue (the edited/AI reply PUT to GBP);</li>
 *   <li>skip → SKIPPED + leaves the DRAFTED queue;</li>
 *   <li>a Claude failure (WireMock 500) → a best-effort GENERIC on-brand draft (no error, still
 *       DRAFTED, non-blank);</li>
 *   <li>a non-chairfill tenant → the paste-in is a hard no-op (1132 module gate);</li>
 *   <li><strong>NMM byte-equivalence:</strong> the unchanged single-arg
 *       {@code GbpReplyDraftService.draftReply(review)} produces a request with NO exemplar block and
 *       the original GBP default prompt (the salon enrichment is additive + per-call only).</li>
 * </ol>
 *
 * <p>Plus the existing {@code GbpReplyDraftServiceIT} / {@code GbpReviewReplyAdminIT} /
 * {@code GbpReviewPollerIT} run unchanged as the NMM/GBP regression gates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        // Force the cheaper Haiku cost-estimate branch (any model works for the WireMock stub).
        "kmosf.gbp.draft-model=claude-haiku-4-5"
})
class SalonReviewReplyIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-cf4-fake";
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
    static void props(DynamicPropertyRegistry registry) {
        // The Anthropic Messages API base-url -> WireMock (POST /). The GBP reply PUT also -> the
        // same WireMock (PUT /v4/.../reply) — distinct URL paths, one stub server.
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
        registry.add("kmosf.gbp.api-base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired GbpReplyDraftService draftService; // for the NMM byte-equivalence assertion
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired GbpReviewReplyRepository reviewReplies;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;
    private String staffToken;
    private String adminToken;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        tenants.save(Tenant.builder()
                .id(tenantId).slug("cf4-it-" + tenantId)
                .displayName("ChairFill CF-4 IT Salon")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill"))
                .aiBudgetUsd(new BigDecimal("5.00")) // non-zero so the budget gate passes
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@cf4.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@cf4.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private void stubAnthropicReply(String replyText) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + replyText + "\"}],"
                                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":40}}")));
    }

    private void stubGbpPostReplyOk() {
        wireMock.stubFor(put(urlPathMatching("/v4/.*/reply"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"comment\":\"posted\"}")));
    }

    private void seedGbpConnection() {
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("google-business")
                .secrets(new HashMap<>(Map.of("accessToken", GBP_ACCESS_TOKEN)))
                .build()).block();
    }

    /** Seeds an approved (POSTED) past reply — the corpus the RAG exemplar source retrieves from. */
    private void seedApprovedExemplar(String reviewId, int rating, String comment, String reply) {
        reviewReplies.save(GbpReviewReply.builder()
                .tenantId(tenantId)
                .reviewId(reviewId)
                .rating(rating)
                .comment(comment)
                .reviewerName("Past Client")
                .draftedReply(reply)
                .status(GbpReviewReply.Status.POSTED)
                .postedAt(Instant.now())
                .receivedAt(Instant.now())
                .build()).block();
    }

    private GbpReviewReply pasteIn(String token, Map<String, Object> body) {
        return web.post().uri("/chairfill/reviews/draft")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(GbpReviewReply.class)
                .returnResult().getResponseBody();
    }

    // -------------------------------------------------------------------------
    // 1. paste-in -> DRAFTED in the queue; salon prompt + RAG exemplar in the Claude request
    // -------------------------------------------------------------------------

    @Test
    void pasteIn_draftsSalonVoicedReply_withBrandTonePromptAndExemplar() {
        // A past approved reply exists -> it must be retrieved as an exemplar and sent to Claude.
        seedApprovedExemplar("reviews/past5", 5,
                "Mia gave me the best balayage of my life!",
                "Thank you so much! Mia will be so happy — can't wait to have you back in her chair!");
        stubAnthropicReply("Thank you, Jordan! We're so glad you loved your cut with Mia. See you next time!");

        GbpReviewReply drafted = pasteIn(staffToken, Map.of(
                "rating", 5,
                "comment", "Loved my haircut with Mia, the vibe was so welcoming.",
                "reviewerName", "Jordan"));

        assertThat(drafted).isNotNull();
        assertThat(drafted.getStatus()).isEqualTo(GbpReviewReply.Status.DRAFTED);
        assertThat(drafted.getDraftedReply()).contains("Mia");
        assertThat(drafted.getReviewId()).startsWith("pasted/");

        // The row is queued DRAFTED and visible to the reused admin list.
        List<GbpReviewReply> queue = web.get().uri("/gbp/review-replies")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(GbpReviewReply.class).returnResult().getResponseBody();
        assertThat(queue).isNotNull();
        assertThat(queue).extracting(GbpReviewReply::getId).contains(drafted.getId());

        // The Claude request carried the SALON brand-tone system prompt (vs the GBP "local service
        // company" default) AND the new review's text...
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.system", containing("salon")))
                .withRequestBody(matchingJsonPath("$.system", containing("client")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Loved my haircut with Mia"))));
        // ...and the RAG exemplar past approved reply (its review + the reply that was given).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("past reviews this business received")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("best balayage of my life")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("can't wait to have you back in her chair"))));
    }

    // -------------------------------------------------------------------------
    // 2. approve (post) -> POSTED + leaves the DRAFTED queue
    // -------------------------------------------------------------------------

    @Test
    void approve_postsReply_andLeavesQueue() {
        seedGbpConnection();
        stubGbpPostReplyOk();
        stubAnthropicReply("Thank you so much, Pat! So glad you loved it.");

        GbpReviewReply drafted = pasteIn(staffToken, Map.of(
                "rating", 5, "comment", "Amazing color work.", "reviewerName", "Pat"));
        assertThat(drafted.getStatus()).isEqualTo(GbpReviewReply.Status.DRAFTED);

        // Approve via the REUSED admin post endpoint.
        GbpReviewReply posted = web.post().uri("/gbp/review-replies/{id}/post", drafted.getId())
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange().expectStatus().isOk()
                .expectBody(GbpReviewReply.class).returnResult().getResponseBody();

        assertThat(posted).isNotNull();
        assertThat(posted.getStatus()).isEqualTo(GbpReviewReply.Status.POSTED);
        assertThat(posted.getPostedAt()).isNotNull();
        // The AI draft was PUT to GBP.
        wireMock.verify(1, putRequestedFor(urlPathMatching("/v4/.*/reply")));

        // It left the DRAFTED queue.
        List<GbpReviewReply> queue = web.get().uri("/gbp/review-replies")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(GbpReviewReply.class).returnResult().getResponseBody();
        assertThat(queue).isNotNull();
        assertThat(queue).extracting(GbpReviewReply::getId).doesNotContain(drafted.getId());
    }

    // -------------------------------------------------------------------------
    // 3. skip -> SKIPPED + leaves the DRAFTED queue
    // -------------------------------------------------------------------------

    @Test
    void skip_marksSkipped_andLeavesQueue() {
        stubAnthropicReply("Thank you, Sam!");

        GbpReviewReply drafted = pasteIn(staffToken, Map.of(
                "rating", 4, "comment", "Good experience.", "reviewerName", "Sam"));

        GbpReviewReply skipped = web.post().uri("/gbp/review-replies/{id}/skip", drafted.getId())
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBody(GbpReviewReply.class).returnResult().getResponseBody();

        assertThat(skipped).isNotNull();
        assertThat(skipped.getStatus()).isEqualTo(GbpReviewReply.Status.SKIPPED);

        List<GbpReviewReply> queue = web.get().uri("/gbp/review-replies")
                .header("Authorization", adminToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(GbpReviewReply.class).returnResult().getResponseBody();
        assertThat(queue).isNotNull();
        assertThat(queue).extracting(GbpReviewReply::getId).doesNotContain(drafted.getId());
        // skip makes NO Google call.
        assertThat(wireMock.findAll(putRequestedFor(urlPathMatching("/v4/.*/reply")))).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 4. Claude failure -> best-effort GENERIC on-brand draft (no error, still DRAFTED, non-blank)
    // -------------------------------------------------------------------------

    @Test
    void claudeFailure_fallsBackToGenericDraft_noError() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        // A critical review -> the gracious generic fallback branch.
        GbpReviewReply drafted = pasteIn(staffToken, Map.of(
                "rating", 2, "comment", "My color came out uneven.", "reviewerName", "Robin"));

        assertThat(drafted).isNotNull();
        assertThat(drafted.getStatus()).isEqualTo(GbpReviewReply.Status.DRAFTED);
        // Never blank — the generic fallback is in the queue to edit before approving.
        assertThat(drafted.getDraftedReply()).isNotBlank();
        assertThat(drafted.getDraftedReply()).containsIgnoringCase("make it right");
        assertThat(drafted.getDraftedReply()).contains("Robin");
        // Claude WAS attempted (best-effort), then degraded.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));
    }

    // -------------------------------------------------------------------------
    // 5. non-chairfill tenant -> hard no-op (1132 module gate)
    // -------------------------------------------------------------------------

    @Test
    void nonChairfillTenant_pasteIn_isModuleGated_1132() {
        // Flip THIS tenant's modules to exclude chairfill (the controller is registered, but the
        // per-tenant module gate rejects).
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of("salon-spa"));
        tenants.save(t).block();

        web.post().uri("/chairfill/reviews/draft")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("rating", 5, "comment", "Great!", "reviewerName", "Lee"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);

        // No row was ledgered.
        assertThat(reviewReplies.findByTenantIdAndStatusOrderByReceivedAtDesc(
                tenantId, GbpReviewReply.Status.DRAFTED).collectList().block()).isEmpty();
        // No Claude call.
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }

    @Test
    void pasteIn_blankComment_4240() {
        web.post().uri("/chairfill/reviews/draft")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("rating", 5, "comment", "   ", "reviewerName", "Lee"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4240);
    }

    @Test
    void pasteIn_nonStaff_isForbidden_1800() {
        // A token with neither STAFF nor ADMIN (PUBLIC-ish): mint a user with an empty role set.
        User noRole = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("norole@cf4.test")
                .roles(Set.of()).status(User.UserStatus.ACTIVE).build();
        users.save(noRole).block();
        String noRoleToken = "Bearer " + jwt.mint(noRole);

        web.post().uri("/chairfill/reviews/draft")
                .header("Authorization", noRoleToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("rating", 5, "comment", "Great!", "reviewerName", "Lee"))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    // -------------------------------------------------------------------------
    // 6. NMM byte-equivalence: the single-arg draftReply has NO exemplar block + the GBP default prompt
    // -------------------------------------------------------------------------

    @Test
    void nmmByteEquivalence_singleArgDraft_usesGbpDefaultPrompt_noExemplars() {
        stubAnthropicReply("Thank you, Jane! We appreciate it.");

        GbpReview review = new GbpReview("reviews/nmm", 5, "Rob got rid of my moles fast!",
                "Jane", Instant.now());

        // The UNCHANGED single-arg entry point (the GBP poller path).
        String reply = draftService.draftReply(review)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(reply).isNotNull();

        // The request used the original GBP default system prompt ("owner-operated local service
        // company"), NOT the salon prompt, and carried NO exemplar/RAG block.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.system", containing("owner-operated local service company")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Rob got rid of my moles"))));
        assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        containing("past reviews this business received"))))).isEmpty();
        assertThat(wireMock.findAll(postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.system", containing("salon"))))).isEmpty();
    }
}
