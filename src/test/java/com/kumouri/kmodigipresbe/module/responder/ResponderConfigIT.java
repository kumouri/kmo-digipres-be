package com.kumouri.kmodigipresbe.module.responder;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
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
 * E2 — ResponderConfigIT: the admin config surface (ADMIN JWT, the NurtureCampaignControllerIT pattern).
 * Proves PUT upsert, GET read (4321 when absent), a blank-intent-name → 4322, a non-ADMIN → 1800, a
 * module-disabled tenant → requireEnabled 1132, and the test-classify admin dry-run (Anthropic →
 * WireMock). Self-clean {@code mongo.remove}; no live external (§7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ResponderConfigIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-responder-cfg-fake";

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

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;

    private static final String VALID_BODY = """
            {
              "enabled": true,
              "vertical": "home",
              "intents": [
                { "name": "CALLBACK_REQUEST", "description": "The customer wants a callback" },
                { "name": "PRICING_QUESTION", "description": "The customer asks about price" }
              ],
              "replyCapPerContactPerDay": 5
            }
            """;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), ResponderConfig.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("responder-cfg-it-" + tenantId)
                .displayName("Responder Cfg IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedAnthropic(tenantId);

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@responder.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@responder.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @Test
    void getBeforeAnyConfig_404_4321() {
        web.get().uri("/responder/config")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4321);
    }

    @Test
    void put_thenGet_roundTrips() {
        web.put().uri("/responder/config")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.enabled").isEqualTo(true)
                .jsonPath("$.vertical").isEqualTo("home")
                .jsonPath("$.intents.length()").isEqualTo(2)
                .jsonPath("$.replyCapPerContactPerDay").isEqualTo(5);

        web.get().uri("/responder/config")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.intents[0].name").isEqualTo("CALLBACK_REQUEST");

        // Exactly one config row per tenant (upsert, not insert-again).
        assertThat(mongo.findAll(ResponderConfig.class).collectList().block()).hasSize(1);
    }

    @Test
    void put_secondTime_updatesInPlace_stillOneRow() {
        web.put().uri("/responder/config").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange().expectStatus().isOk();

        String updated = """
                { "enabled": false, "vertical": "health",
                  "intents": [ { "name": "RESCHEDULE", "description": "move an appt" } ],
                  "replyCapPerContactPerDay": 2 }
                """;
        web.put().uri("/responder/config").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(updated)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.enabled").isEqualTo(false)
                .jsonPath("$.vertical").isEqualTo("health");

        assertThat(mongo.findAll(ResponderConfig.class).collectList().block()).hasSize(1);
    }

    @Test
    void put_blankIntentName_4322() {
        String bad = """
                { "enabled": true, "intents": [ { "name": "  ", "description": "x" } ] }
                """;
        web.put().uri("/responder/config")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(bad)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4322);

        assertThat(mongo.findAll(ResponderConfig.class).collectList().block()).isEmpty();
    }

    @Test
    void nonAdmin_1800() {
        web.put().uri("/responder/config")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(VALID_BODY)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void moduleDisabledTenant_requireEnabled_1132() {
        // A tenant that does NOT carry "responder" in enabledModules → requireEnabled fails 1132.
        UUID otherTenant = UUID.randomUUID();
        tenants.save(Tenant.builder().id(otherTenant).slug("no-responder-" + otherTenant)
                .displayName("No Responder").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of())
                .build()).block();
        User otherAdmin = User.builder().id(UUID.randomUUID()).tenantId(otherTenant)
                .email("admin@noresponder.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(otherAdmin).block();
        String otherToken = "Bearer " + jwt.mint(otherAdmin);

        web.get().uri("/responder/config")
                .header("Authorization", otherToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    @Test
    void testClassify_returnsClassification() {
        // Seed config, stub Anthropic to return a known intent, then dry-run classify.
        web.put().uri("/responder/config").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(VALID_BODY)
                .exchange().expectStatus().isOk();

        stubClassify("CALLBACK_REQUEST", 0.92);

        web.post().uri("/responder/config/test-classify")
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{ \"message\": \"please call me back tomorrow\" }")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.intent").isEqualTo("CALLBACK_REQUEST")
                .jsonPath("$.confidence").isEqualTo(0.92);
    }

    private void seedAnthropic(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubClassify(String intent, double confidence) {
        String json = "{\"intent\":\"" + intent + "\",\"confidence\":" + confidence
                + ",\"extractedSlots\":{}}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + json.replace("\"", "\\\"")
                                + "\"}],\"usage\":{\"input_tokens\":40,\"output_tokens\":12}}")));
    }
}
