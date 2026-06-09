package com.kumouri.kmodigipresbe.integration.gbp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * E3 Review Engine — GbpReviewSentimentAlertIT: the surgical {@code GbpReviewPoller} seam. Opts the
 * default-OFF poller ON and drives {@code pollOnce()} (the {@code GbpReviewPollerIT} pattern); asserts
 * the E3 additive behavior (sentiment stored on each row + a best-effort manager alert on the negative)
 * <strong>and</strong> that the existing poller behavior is preserved (rows DRAFTED with a draft + the
 * 2 {@code GBP_REVIEW_REPLY_DRAFTED} events still fire).
 *
 * <h2>§7 no-live-external</h2>
 * GBP + Anthropic both → ONE WireMock server (disjoint paths). AI-refine is OFF here, so the Anthropic
 * stub serves only the 2 reply-drafts and the sentiment is the deterministic <strong>rating-based</strong>
 * result (2★→NEGATIVE, 5★→POSITIVE) — making the alert assertions deterministic without depending on the
 * model's wording. The Twilio SMS + email notify + the negative-alert seams are {@code @MockitoBean} (the
 * dispatch contract is asserted); no live send.
 *
 * <h2>Cases (in one sweep)</h2>
 * <ul>
 *   <li>2★ review → row sentiment NEGATIVE + a negative manager alert dispatched (email + SMS) +
 *       a {@code GBP_REVIEW_NEGATIVE_ALERTED} event;</li>
 *   <li>5★ review → row sentiment POSITIVE + NO alert;</li>
 *   <li>both rows DRAFTED with a draft + 2 {@code GBP_REVIEW_REPLY_DRAFTED} (existing poller behavior
 *       preserved — the regression invariant within this IT).</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.gbp-reviews.enabled=true",
        "kmosf.modules.gbp-reviews.auto-post=false",
        "kmosf.gbp.draft-model=claude-haiku-4-5",
        "kmosf.review-engine.sentiment-model=claude-haiku-4-5",
        "kmosf.review-engine.negative-rating-threshold=3",
        // Opt the (default-OFF) negative manager alert ON for this IT (AI-refine stays OFF — the rating-
        // based sentiment is the test's determinism anchor, so no extra Anthropic call beyond the draft).
        "kmosf.review-engine.negative-alert-enabled=true"
})
class GbpReviewSentimentAlertIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-gbp-sentiment-fake";
    private static final String GBP_ACCESS_TOKEN = "ya29.gbp-test-access-token-fake";
    private static final String NOTIFY_EMAIL = "rob@nomomole.test";
    private static final String NOTIFY_PHONE = "+16185550111";

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
    static void wireMockProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.gbp.api-base-url", () -> wireMock.baseUrl());
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired GbpReviewPoller poller;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean EmailService emailService;

    private final List<String> smsBodies = new CopyOnWriteArrayList<>();
    private final List<String> emailSubjects = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsBodies.clear();
        emailSubjects.clear();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsBodies.add(req.body());
            return Mono.just(true);
        });
        when(emailService.sendSingleEmail(any(SingleEmailCommunicationRequest.class))).thenAnswer(inv -> {
            SingleEmailCommunicationRequest req = inv.getArgument(0);
            emailSubjects.add(req.subject());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("gbp-sentiment-it-" + tenantId)
                .displayName("GBP Sentiment IT").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("google-business")
                .secrets(new HashMap<>(Map.of("accessToken", GBP_ACCESS_TOKEN)))
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test", "authToken", "tok", "fromNumber", "+16185550100")))
                .config(new HashMap<>(Map.of(
                        "notifyEmail", NOTIFY_EMAIL, "notifyPhone", NOTIFY_PHONE)))
                .build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    private void stubGbpReviews() {
        String reviews = "{\"reviews\":["
                + "{\"reviewId\":\"reviews/pos\",\"starRating\":\"FIVE\","
                + "\"comment\":\"Rob got rid of my moles fast!\","
                + "\"reviewer\":{\"displayName\":\"Jane Doe\"},\"createTime\":\"2026-05-01T10:00:00Z\"},"
                + "{\"reviewId\":\"reviews/neg\",\"starRating\":\"TWO\","
                + "\"comment\":\"Still seeing mounds after the visit.\","
                + "\"reviewer\":{\"displayName\":\"Sam\"},\"createTime\":\"2026-05-02T10:00:00Z\"}"
                + "]}";
        wireMock.stubFor(get(urlPathEqualTo("/v4/reviews"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(reviews)));
    }

    /**
     * The Anthropic stub for the reply-draft (POST /). With AI-refine OFF the sentiment classifier makes
     * no Anthropic call, so the sentiment is the deterministic rating-based result (the test's anchor);
     * this stub only needs to satisfy the reply-draft so the existing poller behavior is exercised.
     */
    private void stubAnthropicGenericReply() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"Thanks for taking the time "
                                + "to share your experience — we appreciate you.\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":24}}")));
    }

    @Test
    void poll_storesSentiment_andAlertsOnNegative_preservingExistingBehavior() {
        stubGbpReviews();
        stubAnthropicGenericReply();

        poller.pollOnce().block();

        List<GbpReviewReply> rows = mongo.findAll(GbpReviewReply.class).collectList().block();
        assertThat(rows).hasSize(2);

        GbpReviewReply pos = rows.stream().filter(r -> "reviews/pos".equals(r.getReviewId()))
                .findFirst().orElseThrow();
        GbpReviewReply neg = rows.stream().filter(r -> "reviews/neg".equals(r.getReviewId()))
                .findFirst().orElseThrow();

        // E3: sentiment stored (rating-based fallback — 5★ POSITIVE, 2★ NEGATIVE).
        assertThat(pos.getSentiment()).isEqualTo(ReviewSentiment.POSITIVE);
        assertThat(neg.getSentiment()).isEqualTo(ReviewSentiment.NEGATIVE);
        assertThat(pos.getSentimentSource()).isNotNull();
        assertThat(neg.getSentimentSource()).isNotNull();

        // E3: exactly one negative alert (for the 2★) — email + SMS dispatched + the event.
        assertThat(emailSubjects).anyMatch(s -> s != null && s.contains("Negative Google review"));
        assertThat(smsBodies).anyMatch(b -> b != null && b.contains("Negative Google review"));
        assertThat(observed).filteredOn(e -> DomainEventType.GBP_REVIEW_NEGATIVE_ALERTED.equals(e.type()))
                .hasSize(1);

        // Existing poller behavior PRESERVED: both rows DRAFTED with a draft + 2 DRAFTED events.
        assertThat(rows).allMatch(r -> r.getStatus() == GbpReviewReply.Status.DRAFTED);
        assertThat(rows).allMatch(r -> r.getDraftedReply() != null && !r.getDraftedReply().isBlank());
        assertThat(observed).filteredOn(e -> DomainEventType.GBP_REVIEW_REPLY_DRAFTED.equals(e.type()))
                .hasSize(2);
        assertThat(observed).noneMatch(e -> DomainEventType.GBP_REVIEW_REPLY_POSTED.equals(e.type()));
    }
}
