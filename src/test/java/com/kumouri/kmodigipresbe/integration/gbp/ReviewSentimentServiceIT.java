package com.kumouri.kmodigipresbe.integration.gbp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.integration.SentimentSource;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3 Review Engine — ReviewSentimentServiceIT: the net-new sentiment classifier against WireMock
 * Anthropic (the {@code GbpReplyDraftServiceIT} pattern). The Anthropic call goes to WireMock via
 * {@code kmosf.ai.anthropic.base-url} ({@code @DynamicPropertySource}); the {@code apiKey} is a sandbox
 * fake. No live external.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>rating-only (no comment) → rating-based: 5→POSITIVE, 3→NEUTRAL, 1→NEGATIVE, source RATING,
 *       and ZERO Anthropic traffic (the AI refine is only for commented reviews);</li>
 *   <li>a commented review + WireMock returns "NEGATIVE" → source AI, sentiment NEGATIVE;</li>
 *   <li>upstream 500 on a commented review → degrades to the rating-based result (source RATING),
 *       never throws.</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.review-engine.sentiment-model=claude-haiku-4-5",
        // Opt the (default-OFF) AI refinement ON for this service-level IT.
        "kmosf.review-engine.ai-refine-enabled=true"
})
class ReviewSentimentServiceIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-sentiment-fake";

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
    static void anthropicProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired ReviewSentimentService sentimentService;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_GBP_REVIEWS"));
        tenants.save(Tenant.builder()
                .id(tenantId).slug("sentiment-it-" + tenantId)
                .displayName("Sentiment IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubAnthropic(String word) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + word + "\"}],"
                                + "\"usage\":{\"input_tokens\":50,\"output_tokens\":2}}")));
    }

    private ReviewSentiment.Result classify(GbpReview review) {
        return sentimentService.classify(review)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
    }

    // -------------------------------------------------------------------------
    // Rating-only -> rating-based, no Anthropic traffic
    // -------------------------------------------------------------------------

    @Test
    void ratingOnly_usesRatingBased_noAnthropicCall() {
        ReviewSentiment.Result five = classify(
                new GbpReview("reviews/5", 5, null, "Jane", Instant.now()));
        assertThat(five.sentiment()).isEqualTo(ReviewSentiment.POSITIVE);
        assertThat(five.source()).isEqualTo(SentimentSource.RATING);

        ReviewSentiment.Result three = classify(
                new GbpReview("reviews/3", 3, null, "Sam", Instant.now()));
        assertThat(three.sentiment()).isEqualTo(ReviewSentiment.NEUTRAL);
        assertThat(three.source()).isEqualTo(SentimentSource.RATING);

        ReviewSentiment.Result one = classify(
                new GbpReview("reviews/1", 1, null, "Pat", Instant.now()));
        assertThat(one.sentiment()).isEqualTo(ReviewSentiment.NEGATIVE);
        assertThat(one.source()).isEqualTo(SentimentSource.RATING);

        // No commented review → the AI refine was never invoked.
        wireMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/")));
    }

    // -------------------------------------------------------------------------
    // Commented review -> AI-refined
    // -------------------------------------------------------------------------

    @Test
    void commentedReview_aiRefines_sourceAi() {
        stubAnthropic("NEGATIVE");

        // A 4-star (rating-based POSITIVE) but the comment is unhappy → AI refines to NEGATIVE.
        ReviewSentiment.Result r = classify(new GbpReview(
                "reviews/mixed", 4, "Decent work but they showed up two hours late and left a mess.",
                "Chris", Instant.now()));

        assertThat(r.sentiment()).isEqualTo(ReviewSentiment.NEGATIVE);
        assertThat(r.source()).isEqualTo(SentimentSource.AI);
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/")));
    }

    // -------------------------------------------------------------------------
    // Upstream failure -> degrade to rating-based, never throws
    // -------------------------------------------------------------------------

    @Test
    void upstreamFailure_degradesToRatingBased() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        // A 5-star commented review: AI fails → fall back to rating-based POSITIVE (not an error).
        ReviewSentiment.Result r = classify(new GbpReview(
                "reviews/err", 5, "Loved it!", "Lee", Instant.now()));

        assertThat(r).isNotNull();
        assertThat(r.sentiment()).isEqualTo(ReviewSentiment.POSITIVE);
        assertThat(r.source()).isEqualTo(SentimentSource.RATING);
    }
}
