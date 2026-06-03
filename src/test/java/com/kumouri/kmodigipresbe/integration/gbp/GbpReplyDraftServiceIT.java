package com.kumouri.kmodigipresbe.integration.gbp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
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
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * NMM GBP review-reply automation — GbpReplyDraftServiceIT: the net-new reply-draft core against
 * WireMock Anthropic (the {@code AnthropicAiAssistService} / Phase-1 {@code VoicemailExtractionService}
 * / Phase-2 {@code MoleVisionServiceIT} test pattern).
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic call goes to WireMock via {@code kmosf.ai.anthropic.base-url}
 * ({@code @DynamicPropertySource}) — never a real host. The {@code apiKey} is a sandbox fake.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>a 5★ review → a drafted reply text returned + the prompt actually went to WireMock with the
 *       review's star rating + comment in the user message (proves the request shape + §7 base-url);</li>
 *   <li>a 1★ critical review → still drafts (the gracious-tone path; the system prompt carries the
 *       low-rating guidance — asserted via the model's canned gracious answer);</li>
 *   <li>upstream non-2xx (500) → DigiPresBeException 1202, no crash (the poller wraps best-effort);</li>
 *   <li>a blank model answer → DigiPresBeException 1202 (never returns a blank draft).</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // Force the cheaper Haiku cost-estimate branch (any model works for the WireMock stub).
        "kmosf.gbp.draft-model=claude-haiku-4-5"
})
class GbpReplyDraftServiceIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-gbp-fake";

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

    @Autowired GbpReplyDraftService draftService;
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
                .id(tenantId).slug("gbp-draft-it-" + tenantId)
                .displayName("GBP Draft IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))  // non-zero so the budget gate passes
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubReply(String replyText) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + replyText + "\"}],"
                                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":40}}")));
    }

    @Test
    void fiveStarReview_draftsReply_andSendsReviewInPrompt() {
        stubReply("Thank you so much, Jane! We're thrilled the moles are gone. Call us anytime.");

        GbpReview review = new GbpReview("reviews/abc", 5, "Rob got rid of my moles fast!",
                "Jane Doe", Instant.now());

        String reply = draftService.draftReply(review)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(reply).isNotNull();
        assertThat(reply).contains("Jane");

        // Proves the request actually went to WireMock (§7) and carried the review's rating + comment.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        com.github.tomakehurst.wiremock.client.WireMock.containing("5 out of 5")))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        com.github.tomakehurst.wiremock.client.WireMock.containing("Rob got rid of my moles"))));
    }

    @Test
    void oneStarCriticalReview_stillDrafts_graciousTone() {
        // The system prompt instructs a gracious make-it-right tone for low ratings; the model's
        // canned answer reflects it. We assert the draft comes back (the low-rating path drafts too).
        stubReply("I'm so sorry to hear that — that's not the experience we want. "
                + "Please reach out directly and we'll make it right.");

        GbpReview review = new GbpReview("reviews/neg", 1, "Still seeing mounds after the visit.",
                "Sam", Instant.now());

        String reply = draftService.draftReply(review)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(reply).isNotNull();
        assertThat(reply).containsIgnoringCase("make it right");
        // The user prompt carried the 1-star rating (the gracious branch is driven by the rating).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content",
                        com.github.tomakehurst.wiremock.client.WireMock.containing("1 out of 5"))));
    }

    @Test
    void upstreamNon2xx_errors1202_noCrash() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        GbpReview review = new GbpReview("reviews/err", 4, "Good service.", "Pat", Instant.now());

        StepVerifier.create(draftService.draftReply(review)
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1202);
                })
                .verify();
    }

    @Test
    void blankModelAnswer_errors1202_neverReturnsBlankDraft() {
        stubReply("");   // model returned an empty text block

        GbpReview review = new GbpReview("reviews/blank", 5, "Great!", "Lee", Instant.now());

        StepVerifier.create(draftService.draftReply(review)
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1202);
                })
                .verify();
    }
}
