package com.kumouri.kmodigipresbe.integration.gbp;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * NMM GBP review-reply automation — GbpReviewPollerIT: the headline poller IT. The poller is gated
 * default-OFF; this IT opts it ON ({@code kmosf.modules.gbp-reviews.enabled=true}) and drives
 * {@code pollOnce()} deterministically (the {@code CoverageNudgeIT} / {@code ImapInboundPoller}
 * pattern).
 *
 * <h2>§7 no-live-external</h2>
 * BOTH the GBP API ({@code kmosf.gbp.api-base-url}) and the Anthropic API
 * ({@code kmosf.ai.anthropic.base-url}) point at ONE WireMock server via {@code @DynamicPropertySource}
 * (distinct paths: GBP {@code GET /v4/reviews} + the reply {@code PUT}, Anthropic {@code POST /}).
 * The Twilio SMS + email notify seams → {@code @MockitoBean} (the {@code MoleTriageIT} / Phase-1
 * precedent — those base URLs are not config-driven, so the seam is mocked; this also asserts the
 * dispatch contract). The OAuth {@code accessToken} + the Anthropic {@code apiKey} are sandbox fakes;
 * no live Google/Anthropic/SMS anywhere.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>WireMock GBP returns 2 reviews → 2 GbpReviewReply(DRAFTED) persisted, each with a drafted
 *       reply (the WireMock Anthropic draft) + notify (email + SMS) dispatched + 2
 *       GBP_REVIEW_REPLY_DRAFTED events; the GBP fetch + 2 Anthropic drafts hit WireMock;</li>
 *   <li>re-poll the SAME review ids → idempotent: still exactly 2 rows, no second draft/notify/event
 *       (the ledger-insert-FIRST + explicit-boolean probe);</li>
 *   <li>auto-post OFF (default) → no PUT /reply hit Google, the rows stay DRAFTED.</li>
 * </ul>
 *
 * <p>Shard-safe: the only mocks are the two precedented notify seams + the WireMock base-URL
 * {@code @DynamicPropertySource}; self-clean {@code mongo.remove} {@code @BeforeEach}; no
 * {@code application-test.properties} / {@code build.gradle} shard change.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // Opt the default-OFF poller ON for this IT; auto-post stays OFF (the default).
        "kmosf.modules.gbp-reviews.enabled=true",
        "kmosf.modules.gbp-reviews.auto-post=false",
        "kmosf.gbp.draft-model=claude-haiku-4-5"
})
class GbpReviewPollerIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-gbp-fake";
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
        // GBP host root + Anthropic messages endpoint both resolve to the one WireMock server;
        // they use disjoint paths (/v4/... vs /).
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

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> emailTo = new AtomicReference<>();

    private UUID tenantId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), GbpReviewReply.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsTo.clear();
        emailTo.set(null);

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            return Mono.just(true);
        });
        when(emailService.sendSingleEmail(any(SingleEmailCommunicationRequest.class))).thenAnswer(inv -> {
            SingleEmailCommunicationRequest req = inv.getArgument(0);
            emailTo.set(req.to() == null ? null : req.to().asString());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("gbp-poller-it-" + tenantId)
                .displayName("GBP Poller IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        // google-business connection — accessToken so GbpApiClient resolves credentials; no
        // config["apiBaseUrl"] so the global kmosf.gbp.api-base-url (-> WireMock) is used.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("google-business")
                .secrets(new HashMap<>(Map.of("accessToken", GBP_ACCESS_TOKEN)))
                .build()).block();

        // anthropic connection — sandbox apiKey so GbpReplyDraftService resolves a key.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        // twilio connection — config carries the per-tenant notify targets (NOT hardcoded);
        // the SMS itself is mocked.
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_gbp",
                        "authToken", "twilio_test_authtoken_gbp",
                        "fromNumber", "+16185550100")))
                .config(new HashMap<>(Map.of(
                        "notifyEmail", NOTIFY_EMAIL,
                        "notifyPhone", NOTIFY_PHONE)))
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
                + "{\"reviewId\":\"reviews/r1\",\"starRating\":\"FIVE\","
                + "\"comment\":\"Rob got rid of my moles fast!\","
                + "\"reviewer\":{\"displayName\":\"Jane Doe\"},\"createTime\":\"2026-05-01T10:00:00Z\"},"
                + "{\"reviewId\":\"reviews/r2\",\"starRating\":\"TWO\","
                + "\"comment\":\"Still seeing mounds after the visit.\","
                + "\"reviewer\":{\"displayName\":\"Sam\"},\"createTime\":\"2026-05-02T10:00:00Z\"}"
                + "]}";
        wireMock.stubFor(get(urlPathEqualTo("/v4/reviews"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(reviews)));
    }

    private void stubAnthropicReply() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"Thank you so much for the kind "
                                + "words! We truly appreciate it.\"}],"
                                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":40}}")));
    }

    // -------------------------------------------------------------------------
    // 2 reviews -> 2 DRAFTED rows + drafts + notify + events; fetch + 2 drafts hit WireMock
    // -------------------------------------------------------------------------

    @Test
    void twoReviews_draftedNotifiedEventsEmitted() {
        stubGbpReviews();
        stubAnthropicReply();

        poller.pollOnce().block();

        List<GbpReviewReply> rows = mongo.findAll(GbpReviewReply.class).collectList().block();
        assertThat(rows).hasSize(2);
        assertThat(rows).allMatch(r -> r.getStatus() == GbpReviewReply.Status.DRAFTED);
        assertThat(rows).allMatch(r -> r.getDraftedReply() != null && !r.getDraftedReply().isBlank());
        assertThat(rows).extracting(GbpReviewReply::getReviewId)
                .containsExactlyInAnyOrder("reviews/r1", "reviews/r2");
        // The review snapshot was captured (rating mapped FIVE->5 / TWO->2, reviewer + comment).
        assertThat(rows).anyMatch(r -> r.getRating() != null && r.getRating() == 5
                && "Jane Doe".equals(r.getReviewerName()));
        assertThat(rows).anyMatch(r -> r.getRating() != null && r.getRating() == 2);

        // Notify dispatched (email + SMS to the per-tenant config targets) — once per review.
        assertThat(emailTo.get()).isEqualTo(NOTIFY_EMAIL);
        assertThat(smsTo).containsOnly(NOTIFY_PHONE);
        assertThat(smsTo).hasSize(2);

        // The GBP fetch + the 2 Anthropic drafts hit WireMock (§7); NO reply was posted (auto-post OFF).
        wireMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/v4/reviews")));
        wireMock.verify(2, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/")));
        wireMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .putRequestedFor(urlPathMatching("/v4/.*/reply")));

        // 2 advisory GBP_REVIEW_REPLY_DRAFTED events; no POSTED event.
        assertThat(observed).filteredOn(e -> DomainEventType.GBP_REVIEW_REPLY_DRAFTED.equals(e.type()))
                .hasSize(2);
        assertThat(observed).noneMatch(e -> DomainEventType.GBP_REVIEW_REPLY_POSTED.equals(e.type()));
    }

    // -------------------------------------------------------------------------
    // Re-poll the same review ids -> idempotent: still 2 rows, zero second effect
    // -------------------------------------------------------------------------

    @Test
    void rePoll_idempotent_noSecondRowsOrEffects() {
        stubGbpReviews();
        stubAnthropicReply();

        poller.pollOnce().block();
        assertThat(mongo.findAll(GbpReviewReply.class).collectList().block()).hasSize(2);

        // Second sweep over the same review ids.
        poller.pollOnce().block();

        // Still exactly 2 rows (no duplicate ledger inserts).
        assertThat(mongo.findAll(GbpReviewReply.class).collectList().block()).hasSize(2);

        // The fetch ran twice (GBP returns the same 2 reviews each time), but the AI draft fired
        // only twice TOTAL (the second sweep skipped both already-ledgered reviews before drafting),
        // and notify fired only twice total (the first sweep).
        wireMock.verify(2, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/v4/reviews")));
        wireMock.verify(2, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlPathEqualTo("/")));
        assertThat(smsTo).hasSize(2);
        assertThat(observed).filteredOn(e -> DomainEventType.GBP_REVIEW_REPLY_DRAFTED.equals(e.type()))
                .hasSize(2);
    }
}
