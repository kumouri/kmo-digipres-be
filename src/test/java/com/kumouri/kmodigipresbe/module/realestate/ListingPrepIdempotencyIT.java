package com.kumouri.kmodigipresbe.module.realestate;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPack;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhoto;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AI-09 — {@code POST /realestate/listings/{listingId}/prep/generate} is now an {@code @IdempotentRoute}:
 * a retried call carrying the SAME {@code Idempotency-Key} must <strong>replay the stored response</strong>
 * (via {@code IdempotencyWebFilter}) instead of re-firing the AI chain — so a double-click / network retry
 * does NOT charge a second full Anthropic spend nor persist a duplicate pack.
 *
 * <h2>How it is made deterministic (no live Anthropic)</h2>
 * Anthropic → WireMock ({@code kmosf.ai.anthropic.base-url}). The listing is created with <strong>no
 * photos</strong> so the vision leg never fires; a generate therefore makes exactly TWO Anthropic POSTs —
 * the marketing description/email call + the net-new 4-week calendar call (the {@code RealEstateListingPrepIT}
 * prompt-disambiguation pattern). The assertion counts Anthropic traffic before/after the replay.
 *
 * <p>Shard-safe: self-clean {@code mongo.remove} {@code @BeforeEach}; no shard/property-file change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true",
        "kmosf.realestate.marketing-model=claude-sonnet-4-6",
        "kmosf.realestate.calendar-model=claude-sonnet-4-6",
        "kmosf.realestate.calendar-vision-model=claude-sonnet-4-5",
        "kmosf.realestate.calendar-posts-per-week=3"
})
class ListingPrepIdempotencyIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-t10-idem-fake";

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
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        for (Class<?> c : List.of(Listing.class, ListingPhoto.class, ListingPrepPack.class,
                IntegrationConnection.class, User.class, Tenant.class)) {
            mongo.remove(new Query(), c).block();
        }
        // No idempotency-ledger clear needed: each test mints a fresh random Idempotency-Key under a fresh
        // random tenant, so the (tenant, route, key) composite never collides across tests (the suite-wide
        // convention).

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("t10-idem-" + tenantId)
                .displayName("Harbor Point Realty T10 Idempotency IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("realestate"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff-" + tenantId + "@t10.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @Test
    void retriedGenerate_sameIdempotencyKey_replaysWithoutSecondAiSpend() {
        stubMarketingGeneration(cleanMarketingJson());
        stubCalendarGeneration(cleanCalendarJson());

        Listing listing = createListing("128 Lighthouse Way");
        String key = UUID.randomUUID().toString();

        // First call → real generation: a DRAFTED pack + exactly two Anthropic calls (marketing + calendar).
        byte[] firstBody = web.post()
                .uri("/realestate/listings/{id}/prep/generate", listing.getId())
                .header("Authorization", staffToken)
                .header("Idempotency-Key", key)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().doesNotExist("Idempotency-Replayed")
                .expectBody().jsonPath("$.status").isEqualTo("DRAFTED")
                .returnResult().getResponseBody();
        assertThat(firstBody).isNotNull();

        int callsAfterFirst = anthropicCalls();
        assertThat(callsAfterFirst).isEqualTo(2); // marketing + calendar, no vision (no photos)

        long packsAfterFirst = mongo.count(new Query(), ListingPrepPack.class).block();
        assertThat(packsAfterFirst).isEqualTo(1L);

        // Retry with the SAME key → replayed from the ledger: identical body, the replay header, and
        // CRUCIALLY no additional Anthropic traffic (no second AI spend) and no duplicate pack.
        byte[] replayBody = web.post()
                .uri("/realestate/listings/{id}/prep/generate", listing.getId())
                .header("Authorization", staffToken)
                .header("Idempotency-Key", key)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Idempotency-Replayed", "true")
                .expectBody().jsonPath("$.status").isEqualTo("DRAFTED")
                .returnResult().getResponseBody();

        assertThat(replayBody).isEqualTo(firstBody);          // byte-identical replay
        assertThat(anthropicCalls()).isEqualTo(callsAfterFirst); // ZERO extra AI spend
        assertThat(mongo.count(new Query(), ListingPrepPack.class).block()).isEqualTo(1L); // no dup pack
    }

    @Test
    void generate_missingIdempotencyKey_is3100() {
        stubMarketingGeneration(cleanMarketingJson());
        stubCalendarGeneration(cleanCalendarJson());
        Listing listing = createListing("456 Pine Ave");

        web.post()
                .uri("/realestate/listings/{id}/prep/generate", listing.getId())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3100);

        // The route never ran the handler → no AI spend.
        assertThat(anthropicCalls()).isZero();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private int anthropicCalls() {
        return wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/")).build()).getCount();
    }

    private Listing createListing(String address) {
        return listingService.create(Listing.builder()
                        .addressLine(address).city("Galveston").state("TX").zip("77550")
                        .price(new BigDecimal("539000")).beds(4).baths(new BigDecimal("3"))
                        .sqft(2450)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    private TenantContext ctx() {
        return new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    private void stubMarketingGeneration(String innerEscaped) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.containing("marketing copywriter drafting listing marketing"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicBody(innerEscaped))));
    }

    private void stubCalendarGeneration(String innerEscaped) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.containing("FOUR-WEEK social media CALENDAR"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicBody(innerEscaped))));
    }

    private static String anthropicBody(String innerEscaped) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + innerEscaped + "\"}],"
                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":120}}";
    }

    private static String cleanMarketingJson() {
        return "{\\\"mlsRemarks\\\":\\\"A charming 4-bedroom coastal home with granite countertops.\\\","
                + "\\\"instagram\\\":\\\"New listing! #realestate\\\","
                + "\\\"facebook\\\":\\\"Just listed.\\\","
                + "\\\"x\\\":\\\"Just listed: 4BR coastal.\\\","
                + "\\\"emailBlast\\\":\\\"Hello, we are excited to share this new listing.\\\"}";
    }

    private static String cleanCalendarJson() {
        return "{\\\"posts\\\":["
                + "{\\\"week\\\":1,\\\"dayOffset\\\":0,\\\"channel\\\":\\\"instagram\\\","
                + "\\\"copy\\\":\\\"Just listed! A stunning coastal retreat. #newlisting\\\"},"
                + "{\\\"week\\\":2,\\\"dayOffset\\\":7,\\\"channel\\\":\\\"facebook\\\","
                + "\\\"copy\\\":\\\"Feature spotlight: the granite kitchen.\\\"}"
                + "]}";
    }
}
