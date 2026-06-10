package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.realestate.model.DisclosureType;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingDisclosureService;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.Nullable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 (Real Estate "Midnight Responder") — response-latency instrumentation. Proves a grounded inbound turn
 * records the received→replied latency on the assistant turn, and the
 * {@code GET /realestate/responder/latency-stats} endpoint aggregates p50/p95 + the after-hours share.
 *
 * <p>The grounded-answer path is exercised end-to-end (the {@code RealEstateQualificationIT} machinery:
 * Anthropic→WireMock matched by body, an in-memory {@link VectorIndex}, {@link TwilioSmsService} a
 * {@code @MockitoBean}). The latency-stats read uses the STAFF + both-module gate. No live external (§7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, MidnightResponderLatencyIT.InMemoryVectorIndexConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true",
        "kmosf.realestate.concierge-model=claude-haiku-4-5",
        "kmosf.realestate.retrieval-top-k=12"
})
class MidnightResponderLatencyIT {

    private static final String AUTH_TOKEN = "twilio_test_midnight_latency";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-midnight-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String TRACKED_NUMBER = "+12145559401";
    private static final String BUYER = "+12145550500";
    private static final String STAFF_TOKEN_SUBJECT = "staff@midnight-latency.example";

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void anthropicProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired ListingService listingService;
    @Autowired ListingDisclosureService disclosureService;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired InMemoryVectorIndex vectorIndex;
    @Autowired JwtTokenService jwt;
    @Autowired UserRepository users;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Listing.class).block();
        mongo.remove(new Query(), ListingDisclosure.class).block();
        mongo.remove(new Query(), ConciergeConversation.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), MidnightResponderConfig.class).block();
        mongo.remove(new Query(), User.class).block();
        vectorIndex.clear();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder().id(tenantId).slug("midnight-latency-" + tenantId)
                .displayName("Midnight Latency IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("realestate", "responder", "nurture"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email(STAFF_TOKEN_SUBJECT)
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
        seedAnthropic();
        seedTwilio();
    }

    @Test
    void groundedTurn_recordsLatency_andStatsEndpointAggregates() {
        Listing listing = listingService.create(Listing.builder()
                        .addressLine("742 Magnolia Ave").city("Dallas").state("TX").zip("75204")
                        .trackedPhone(TRACKED_NUMBER).build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
        disclosureService.create(listing.getId(), ListingDisclosure.builder()
                        .disclosureType(DisclosureType.ROOF)
                        .text("Roof replaced 2021, architectural shingles, transferable warranty.").build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
        stubAnswer("The roof was replaced in 2021 with a transferable warranty.");

        postSms(BUYER, TRACKED_NUMBER, "how old is the roof?").expectStatus().isOk();

        // The assistant turn carries a recorded received→replied latency.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            ConciergeConversation conv = onlyConversation();
            ConciergeTurn assistant = conv.getTurns().stream()
                    .filter(t -> t.getRole() == ConciergeTurn.Role.ASSISTANT)
                    .reduce((a, b) -> b).orElse(null);
            assertThat(assistant).isNotNull();
            assertThat(assistant.getLatencyMs()).as("assistant turn latency recorded").isNotNull();
            assertThat(assistant.getLatencyMs()).isGreaterThanOrEqualTo(0L);
            assertThat(assistant.getReceivedAt()).isNotNull();
        });

        // The stats endpoint aggregates the latency + the after-hours share.
        MidnightResponderLatencyStats stats = web.get()
                .uri("/realestate/responder/latency-stats")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MidnightResponderLatencyStats.class)
                .returnResult().getResponseBody();

        assertThat(stats.repliedTurns()).isEqualTo(1L);
        assertThat(stats.p50LatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(stats.p95LatencyMs()).isGreaterThanOrEqualTo(0L);
        assertThat(stats.maxLatencyMs()).isGreaterThanOrEqualTo(stats.p50LatencyMs());
        // One buyer turn was received; the after-hours share is in [0,1].
        assertThat(stats.totalBuyerTurns()).isEqualTo(1L);
        assertThat(stats.afterHoursShare()).isBetween(0.0, 1.0);
        assertThat(stats.afterHoursStartHour()).isEqualTo(8);
        assertThat(stats.afterHoursEndHour()).isEqualTo(18);
    }

    @Test
    void latencyStats_emptyWhenNoConversations() {
        MidnightResponderLatencyStats stats = web.get()
                .uri("/realestate/responder/latency-stats")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody(MidnightResponderLatencyStats.class)
                .returnResult().getResponseBody();

        assertThat(stats.repliedTurns()).isZero();
        assertThat(stats.p50LatencyMs()).isZero();
        assertThat(stats.totalBuyerTurns()).isZero();
        assertThat(stats.afterHoursShare()).isZero();
    }

    // ── helpers ──

    private TenantContext ctx() {
        return new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    private ConciergeConversation onlyConversation() {
        List<ConciergeConversation> all = mongo.findAll(ConciergeConversation.class).collectList().block();
        assertThat(all).hasSize(1);
        return all.get(0);
    }

    private WebTestClient.ResponseSpec postSms(String from, String to, String body) {
        String path = "/public/integrations/twilio/" + tenantId + "/sms";
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("MessageSid", "SM_test_" + UUID.randomUUID());
        form.add("From", from);
        form.add("To", to);
        form.add("Body", body);
        String sig = sign("https://" + FORWARDED_HOST + path, form);
        return web.post().uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange();
    }

    private static String sign(String fullUrl, MultiValueMap<String, String> form) {
        StringBuilder sb = new StringBuilder(fullUrl);
        TreeMap<String, String> sorted = new TreeMap<>();
        form.forEach((k, v) -> sorted.put(k, v.isEmpty() ? "" : v.get(0)));
        sorted.forEach((k, v) -> sb.append(k).append(v));
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(AUTH_TOKEN.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder().encodeToString(
                    mac.doFinal(sb.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException("HMAC-SHA1 failed", ex);
        }
    }

    private void stubAnswer(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(containing("DISCLOSURE CONTEXT"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}")));
    }

    private void seedAnthropic() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void seedTwilio() {
        Map<String, String> config = new HashMap<>();
        config.put("smsMode", "realestate");
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of("authToken", AUTH_TOKEN, "fromNumber", TRACKED_NUMBER)))
                .config(config)
                .build()).block();
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryVectorIndexConfig {
        @Bean
        @Primary
        InMemoryVectorIndex inMemoryVectorIndex() {
            return new InMemoryVectorIndex();
        }
    }

    static class InMemoryVectorIndex implements VectorIndex {
        record Key(UUID tenantId, String sourceType, UUID sourceId) {
        }

        record Stored(String sourceType, UUID sourceId, Map<String, Object> metadata) {
        }

        private final Map<Key, Stored> store = new ConcurrentHashMap<>();

        void clear() {
            store.clear();
        }

        @Override
        public Mono<Void> upsert(UUID tenantId, String sourceType, UUID sourceId,
                                 float[] vector, Map<String, Object> metadata) {
            store.put(new Key(tenantId, sourceType, sourceId), new Stored(sourceType, sourceId, metadata));
            return Mono.empty();
        }

        @Override
        public Mono<Void> delete(UUID tenantId, String sourceType, UUID sourceId) {
            store.remove(new Key(tenantId, sourceType, sourceId));
            return Mono.empty();
        }

        @Override
        public Flux<VectorSearchHit> search(UUID tenantId, float[] queryVector, int topK,
                                            @Nullable String keywordFilter) {
            return Flux.fromIterable(store.entrySet().stream()
                    .filter(e -> e.getKey().tenantId().equals(tenantId))
                    .map(e -> new VectorSearchHit(e.getValue().sourceType(), e.getValue().sourceId(),
                            0.9, e.getValue().metadata()))
                    .limit(topK)
                    .toList());
        }
    }
}
