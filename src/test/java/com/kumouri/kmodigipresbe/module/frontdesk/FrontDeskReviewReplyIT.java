package com.kumouri.kmodigipresbe.module.frontdesk;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.frontdesk.reviews.HipaaReplyLint;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrontDesk IQ (FD-4) — FrontDeskReviewReplyIT: the <strong>HIPAA-safe review-reply</strong>, the flagship's
 * signature demo (fence F4). Drives the FrontDesk paste-in surface ({@code POST /frontdesk/reviews/draft}) +
 * the self-contained draft → approve/skip queue ({@code GET /frontdesk/reviews}, {@code POST /{id}/approve}|
 * {@code /{id}/skip}) over HTTP ({@code WebTestClient}, the {@code SalonReviewReplyIT} pattern). Anthropic →
 * WireMock via {@code kmosf.ai.anthropic.base-url}. No live external (§7).
 *
 * <h2>Coverage (plan FD-4 ITs + the F4 hard gate)</h2>
 * <ol>
 *   <li><strong>The headline / F4:</strong> an adversarial 1-star review that explicitly names a procedure ("the
 *       dentist botched my crown and the root canal was a disaster") → a DRAFTED reply that contains NONE of a
 *       forbidden patient-status / procedure token set, and the HIPAA-guardrail system prompt was sent to
 *       Claude. (WireMock returns a compliant draft; the assertion is the absence of the forbidden set.)</li>
 *   <li><strong>The lint catches a crafted leak:</strong> the deterministic {@link HipaaReplyLint} flags a
 *       draft that DOES confirm patient status / name a procedure (a model could regress; the lint is the
 *       backstop), and a clean draft yields no flags — surfaced on the queue-row.</li>
 *   <li><strong>approve → copy-ready:</strong> approve a DRAFTED row → POSTED, leaves the queue, and makes NO
 *       Google call (the demo path).</li>
 *   <li><strong>never auto-post:</strong> drafting alone never posts (no GBP/Google request is ever made).</li>
 *   <li><strong>best-effort on a Claude failure:</strong> WireMock 500 → a generic HIPAA-safe fallback (no
 *       error, still DRAFTED, non-blank, and itself clean of the forbidden set even though the review named a
 *       procedure — the fallback never echoes the review).</li>
 *   <li>skip → SKIPPED + leaves the queue; blank comment → 4290; a non-frontdesk tenant → 1132 module gate.</li>
 * </ol>
 *
 * <p>The {@code GbpReplyDraftServiceIT} / {@code GbpReviewReplyAdminIT} / {@code SalonReviewReplyIT} run
 * unchanged as the {@code GbpReplyDraftService} / NMM / ChairFill byte-equivalence gates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true",
        // Force the cheaper Haiku cost-estimate branch (any model works for the WireMock stub).
        "kmosf.gbp.draft-model=claude-haiku-4-5"
})
class FrontDeskReviewReplyIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-fd4-fake";

    /**
     * The forbidden token/phrase set the F4 assertion requires to be ABSENT from a HIPAA-safe drafted reply —
     * a patient-status confirmation or a procedure/treatment named in the adversarial review. Case-insensitive.
     */
    private static final List<String> FORBIDDEN_TOKENS = List.of(
            "patient", "crown", "root canal", "filling", "procedure", "treatment", "surgery",
            "diagnosis", "prescription", "your visit", "your appointment", "being our patient");

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
        // The Anthropic Messages API base-url -> WireMock (POST /). No GBP base-url override is needed: FD-4
        // approval is copy-ready (no live Google call), so any PUT to a GBP URL would be a never-auto-post bug.
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired GbpReviewReplyRepository reviewReplies;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("fd4-it-" + tenantId)
                .displayName("FrontDesk IQ FD-4 IT Dental")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk"))
                .aiBudgetUsd(new BigDecimal("5.00")) // non-zero so the budget gate passes
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@fd4.test")
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

    private void stubAnthropicReply(String replyText) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + replyText + "\"}],"
                                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":40}}")));
    }

    /** The FD-4 queue-row DTO shape ({@code {reply, hipaaFlags}}) as JSON for WebTestClient deserialization. */
    private static final ParameterizedTypeReference<Map<String, Object>> DRAFT_DTO =
            new ParameterizedTypeReference<>() {
            };

    @SuppressWarnings("unchecked")
    private static String draftedReplyText(Map<String, Object> dto) {
        Map<String, Object> reply = (Map<String, Object>) dto.get("reply");
        return reply == null ? null : (String) reply.get("draftedReply");
    }

    @SuppressWarnings("unchecked")
    private static String draftStatus(Map<String, Object> dto) {
        Map<String, Object> reply = (Map<String, Object>) dto.get("reply");
        return reply == null ? null : (String) reply.get("status");
    }

    @SuppressWarnings("unchecked")
    private static String draftId(Map<String, Object> dto) {
        Map<String, Object> reply = (Map<String, Object>) dto.get("reply");
        return reply == null ? null : (String) reply.get("id");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> flags(Map<String, Object> dto) {
        return (List<Map<String, Object>>) dto.get("hipaaFlags");
    }

    private Map<String, Object> pasteIn(String token, Map<String, Object> body) {
        return web.post().uri("/frontdesk/reviews/draft")
                .header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(DRAFT_DTO)
                .returnResult().getResponseBody();
    }

    private static void assertNoForbiddenToken(String reply) {
        assertThat(reply).isNotBlank();
        String lower = reply.toLowerCase(Locale.ROOT);
        for (String token : FORBIDDEN_TOKENS) {
            assertThat(lower)
                    .as("HIPAA-safe reply must NOT contain the forbidden token '%s' — reply was: %s",
                            token, reply)
                    .doesNotContain(token);
        }
    }

    // -------------------------------------------------------------------------
    // 1. THE HEADLINE (F4): adversarial review naming a procedure -> a DRAFTED reply with NO forbidden token,
    //    and the HIPAA-guardrail system prompt was sent to Claude.
    // -------------------------------------------------------------------------

    @Test
    void adversarialReview_namesProcedure_draftIsHipaaSafe_andUsesGuardrailPrompt() {
        // A compliant, HIPAA-safe draft (what a guardrailed model returns): thank / apologize / invite-offline,
        // no patient-status confirmation, no procedure named — even though the review screamed "crown"/"root canal".
        stubAnthropicReply("Hi Dana, thank you for taking the time to share this. We're sorry to hear your "
                + "experience fell short of your expectations, and we take feedback seriously. We'd welcome the "
                + "chance to make things right \\u2014 please give our office a call so we can talk it through.");

        Map<String, Object> dto = pasteIn(staffToken, Map.of(
                "rating", 1,
                "comment", "Worst experience ever. The dentist completely botched my crown and the root canal "
                        + "was a disaster. I needed a prescription afterward and they overcharged me.",
                "reviewerName", "Dana"));

        assertThat(dto).isNotNull();
        assertThat(draftStatus(dto)).isEqualTo("DRAFTED");
        // THE F4 ASSERTION: the drafted public reply confirms no patient status and names no procedure.
        assertNoForbiddenToken(draftedReplyText(dto));
        // The compliant draft is clean per the deterministic lint too.
        assertThat(flags(dto)).isEmpty();

        // The HIPAA-guardrail system prompt was the one sent to Claude (the F4 prompt clauses).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.system", containing("HIPAA")))
                .withRequestBody(matchingJsonPath("$.system", containing("NEVER confirm")))
                .withRequestBody(matchingJsonPath("$.system",
                        containing("procedure, treatment, diagnosis"))));
        // The review text (incl. its clinical terms) was given to the model as INPUT — the guardrail is what
        // keeps those terms OUT of the OUTPUT (we do not strip the input; we constrain the generation).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("botched my crown"))));

        // NEVER auto-posted: drafting made no Google/GBP call whatsoever (only the one Anthropic POST).
        assertThat(wireMock.findAll(anyRequestedFor(urlPathMatching("/v4/.*")))).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 2. The deterministic lint catches a crafted leak (the backstop if a model regresses).
    // -------------------------------------------------------------------------

    @Test
    void hipaaLint_flagsACraftedLeak_andPassesACleanDraft() {
        // A leaky reply a non-compliant model might produce: confirms patient status AND names a procedure.
        String leak = "Thank you for being our patient, Dana! We're sorry your root canal and crown were not "
                + "a great experience. Your treatment matters to us.";
        List<HipaaReplyLint.HipaaFlag> leakFlags = HipaaReplyLint.lint(leak);
        assertThat(leakFlags).isNotEmpty();
        assertThat(leakFlags).extracting(HipaaReplyLint.HipaaFlag::category)
                .contains(HipaaReplyLint.HipaaFlag.Category.PATIENT_STATUS,
                        HipaaReplyLint.HipaaFlag.Category.CLINICAL);
        assertThat(leakFlags).extracting(HipaaReplyLint.HipaaFlag::term)
                .contains("being our patient", "root canal", "crown", "your treatment");
        assertThat(HipaaReplyLint.isClean(leak)).isFalse();

        // A compliant reply yields no flags.
        String clean = "Hi Dana, thank you for sharing this. We're sorry your experience fell short — please "
                + "call our office so we can make it right.";
        assertThat(HipaaReplyLint.lint(clean)).isEmpty();
        assertThat(HipaaReplyLint.isClean(clean)).isTrue();

        // And the leak surfaces on the queue-row when the (stubbed) model returns it — flags are visible to staff.
        stubAnthropicReply("Thank you for being our patient, Dana! Your root canal will heal soon.");
        Map<String, Object> dto = pasteIn(staffToken, Map.of(
                "rating", 1, "comment", "My crown hurt.", "reviewerName", "Dana"));
        assertThat(draftStatus(dto)).isEqualTo("DRAFTED");
        assertThat(flags(dto)).isNotEmpty(); // the lint flagged the leaked draft on the row
    }

    // -------------------------------------------------------------------------
    // 3. approve -> copy-ready (POSTED, leaves the queue, NO Google call).
    // -------------------------------------------------------------------------

    @Test
    void approve_marksCopyReady_andLeavesQueue_withNoGoogleCall() {
        stubAnthropicReply("Thank you for taking the time to share this. Please call our office and we'll help.");

        Map<String, Object> drafted = pasteIn(staffToken, Map.of(
                "rating", 2, "comment", "Long wait and rude staff.", "reviewerName", "Sam"));
        assertThat(draftStatus(drafted)).isEqualTo("DRAFTED");
        String id = draftId(drafted);

        Map<String, Object> approved = web.post().uri("/frontdesk/reviews/{id}/approve", id)
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(DRAFT_DTO).returnResult().getResponseBody();

        assertThat(approved).isNotNull();
        assertThat(draftStatus(approved)).isEqualTo("POSTED");

        // It left the DRAFTED queue.
        List<Map<String, Object>> queue = web.get().uri("/frontdesk/reviews")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(DRAFT_DTO).returnResult().getResponseBody();
        assertThat(queue).isNotNull();
        assertThat(queue).extracting(FrontDeskReviewReplyIT::draftId).doesNotContain(id);

        // Copy-ready = NO live Google call (never-auto-post; the only external call was the one Anthropic draft).
        assertThat(wireMock.findAll(anyRequestedFor(urlPathMatching("/v4/.*")))).isEmpty();

        // Approving again is rejected by the same-status guard (4291).
        web.post().uri("/frontdesk/reviews/{id}/approve", id)
                .header("Authorization", staffToken)
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4291);
    }

    // -------------------------------------------------------------------------
    // 4. best-effort: a Claude failure -> a generic HIPAA-safe fallback (no error, DRAFTED, non-blank, clean).
    // -------------------------------------------------------------------------

    @Test
    void claudeFailure_fallsBackToGenericHipaaSafeDraft_noError_andStillClean() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        // An adversarial critical review that names procedures -> the fallback must NOT echo them.
        Map<String, Object> dto = pasteIn(staffToken, Map.of(
                "rating", 1,
                "comment", "The filling fell out and the extraction got infected. Terrible.",
                "reviewerName", "Robin"));

        assertThat(dto).isNotNull();
        assertThat(draftStatus(dto)).isEqualTo("DRAFTED");
        String reply = draftedReplyText(dto);
        assertThat(reply).isNotBlank();
        assertThat(reply).contains("Robin");
        // The generic fallback is HIPAA-safe by construction: no patient-status/procedure token (it does not
        // echo the review), and the deterministic lint agrees it is clean.
        assertNoForbiddenToken(reply);
        assertThat(flags(dto)).isEmpty();
        // Claude WAS attempted (best-effort), then degraded.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));
        // Still never posted.
        assertThat(wireMock.findAll(anyRequestedFor(urlPathMatching("/v4/.*")))).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 5. skip -> SKIPPED + leaves the queue (no Google call).
    // -------------------------------------------------------------------------

    @Test
    void skip_marksSkipped_andLeavesQueue() {
        stubAnthropicReply("Thank you for the kind words! We look forward to seeing you again.");

        Map<String, Object> drafted = pasteIn(staffToken, Map.of(
                "rating", 5, "comment", "Friendly front desk!", "reviewerName", "Lee"));
        String id = draftId(drafted);

        Map<String, Object> skipped = web.post().uri("/frontdesk/reviews/{id}/skip", id)
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(DRAFT_DTO).returnResult().getResponseBody();
        assertThat(skipped).isNotNull();
        assertThat(draftStatus(skipped)).isEqualTo("SKIPPED");

        List<Map<String, Object>> queue = web.get().uri("/frontdesk/reviews")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(DRAFT_DTO).returnResult().getResponseBody();
        assertThat(queue).isNotNull();
        assertThat(queue).extracting(FrontDeskReviewReplyIT::draftId).doesNotContain(id);
        assertThat(wireMock.findAll(anyRequestedFor(urlPathMatching("/v4/.*")))).isEmpty();
    }

    // -------------------------------------------------------------------------
    // 6. blank comment -> 4290; non-frontdesk tenant -> 1132 module gate.
    // -------------------------------------------------------------------------

    @Test
    void pasteIn_blankComment_4290() {
        web.post().uri("/frontdesk/reviews/draft")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("rating", 5, "comment", "   ", "reviewerName", "Lee"))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4290);
    }

    @Test
    void nonFrontdeskTenant_pasteIn_isModuleGated_1132() {
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of()); // strip frontdesk
        tenants.save(t).block();

        web.post().uri("/frontdesk/reviews/draft")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("rating", 5, "comment", "Great!", "reviewerName", "Lee"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);

        // No row ledgered, no Claude call.
        assertThat(reviewReplies.findByTenantIdAndStatusOrderByReceivedAtDesc(
                tenantId, GbpReviewReply.Status.DRAFTED).collectList().block()).isEmpty();
        assertThat(wireMock.findAll(anyRequestedFor(anyUrl()))).isEmpty();
    }
}
