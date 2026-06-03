package com.kumouri.kmodigipresbe.integration.molevision;

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
import java.util.HashMap;
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
 * Phase 2 — MoleVisionServiceIT: the net-new vision-classify core against WireMock Anthropic
 * (the {@code AnthropicAiAssistService} / Phase-1 {@code VoicemailExtractionService} test pattern).
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic vision call goes to WireMock via {@code kmosf.ai.anthropic.base-url}
 * ({@code @DynamicPropertySource}) — never a real host. The {@code apiKey} is a sandbox fake.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>canned high-confidence "mole" classification → parsed category/confidence/rationale +
 *       the image content block was actually sent (proves the vision request shape + §7 base-url);</li>
 *   <li>prose-wrapped / code-fenced JSON → still parsed (defensive parse);</li>
 *   <li>garbage (non-JSON) answer → degrades to UNSURE (never throws);</li>
 *   <li>blank image bytes → UNSURE without any WireMock traffic (no spend);</li>
 *   <li>upstream non-2xx → DigiPresBeException 1202 (the reused AI upstream code).</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // Force the cheaper Haiku model path for the cost-estimate branch (any model works).
        "kmosf.mole-triage.vision-model=claude-haiku-4-5"
})
class MoleVisionServiceIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-phase2-fake";
    private static final byte[] FAKE_IMAGE = "fake-jpeg-bytes".getBytes();

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

    @Autowired MoleVisionService visionService;
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
        ctx = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
        tenants.save(Tenant.builder()
                .id(tenantId).slug("molevision-it-" + tenantId)
                .displayName("MoleVision IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))  // non-zero so the budget gate passes
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void stubVision(String innerJsonEscaped) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + innerJsonEscaped + "\"}],"
                                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":30}}")));
    }

    @Test
    void cannedMoleClassification_parsesAndSendsImageBlock() {
        // Strict JSON the prompt asks for, escaped for embedding in the outer JSON body.
        String inner = "{\\\"classification\\\":\\\"mole\\\",\\\"confidence\\\":0.92,"
                + "\\\"rationale\\\":\\\"conical volcano-shaped soil mound\\\"}";
        stubVision(inner);

        MoleClassification result = visionService.classify(FAKE_IMAGE, "image/jpeg")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.category()).isEqualTo(MoleClassificationCategory.MOLE);
        assertThat(result.confidence()).isEqualTo(0.92);
        assertThat(result.rationale()).contains("conical");
        assertThat(result.isPest()).isTrue();

        // Proves the request actually went to WireMock (§7) and carried a base64 image block.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.messages[0].content[0].type",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo("image")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.messages[0].content[0].source.type",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo("base64"))));
    }

    @Test
    void proseWrappedJson_stillParses_defensive() {
        String inner = "Here is my assessment: ```json {\\\"classification\\\":\\\"vole\\\","
                + "\\\"confidence\\\":0.55,\\\"rationale\\\":\\\"surface runways\\\"} ``` Hope that helps!";
        stubVision(inner);

        MoleClassification result = visionService.classify(FAKE_IMAGE, "image/png")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.category()).isEqualTo(MoleClassificationCategory.VOLE);
        assertThat(result.confidence()).isEqualTo(0.55);
    }

    @Test
    void garbageNonJsonAnswer_degradesToUnsure_neverThrows() {
        stubVision("I cannot tell what this is from the photo, sorry.");

        MoleClassification result = visionService.classify(FAKE_IMAGE, "image/jpeg")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.category()).isEqualTo(MoleClassificationCategory.UNSURE);
        assertThat(result.confidence()).isEqualTo(0.0);
        assertThat(result.isPest()).isFalse();
    }

    @Test
    void blankImage_shortCircuitsToUnsure_noTraffic() {
        MoleClassification result = visionService.classify(new byte[0], "image/jpeg")
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.category()).isEqualTo(MoleClassificationCategory.UNSURE);
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void upstreamNon2xx_errors1202() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        StepVerifier.create(visionService.classify(FAKE_IMAGE, "image/jpeg")
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1202);
                })
                .verify();
    }
}
