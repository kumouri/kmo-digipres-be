package com.kumouri.kmodigipresbe.service.ai.vision;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
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
 * AiVisionServiceIT: the reusable Anthropic-vision transport ({@link AiVisionService}) against
 * WireMock — the vertical-agnostic sibling of {@code MoleVisionServiceIT}. Uses a NON-mole taxonomy
 * (a home-services trade triage) to prove the transport is generic: the {@code model},
 * {@code systemPrompt}, and {@code userText} are caller-supplied method parameters.
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic vision call goes to WireMock via {@code kmosf.ai.anthropic.base-url}
 * ({@code @DynamicPropertySource}) — never a real host. The {@code apiKey} is a sandbox fake.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>{@code classify} a non-mole taxonomy → parsed label/confidence + the request carried an
 *       image content block (proves the vision request shape + §7 base-url);</li>
 *   <li>{@code extract} open JSON → the raw JsonNode is returned;</li>
 *   <li>garbage (non-JSON) answer → {@code classify} degrades to {@link VisionClassification#unsure()},
 *       {@code extract} to an empty object node (never throws);</li>
 *   <li>blank image bytes → unsure / empty node without any WireMock traffic (no spend);</li>
 *   <li>upstream non-2xx → DigiPresBeException 1202 (the reused AI upstream code).</li>
 * </ul>
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class AiVisionServiceIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-aivision-fake";
    private static final byte[] FAKE_IMAGE = "fake-image-bytes".getBytes();
    private static final String MODEL = "claude-haiku-4-5";

    private static final String TRADE_SYSTEM_PROMPT =
            "Classify the home-services trade shown in this photo. Respond with ONLY a single "
            + "minified JSON object with keys \"classification\" (one of \"hvac\", \"plumbing\", "
            + "\"electrical\", or \"none\"), \"confidence\" (0.0-1.0), and \"rationale\".";
    private static final String TRADE_USER_TEXT =
            "Classify the trade in this photo. Return ONLY the JSON object.";

    private static final String EXTRACT_SYSTEM_PROMPT =
            "Read the equipment nameplate in this photo and return ONLY a minified JSON object with "
            + "whatever of \"make\", \"model\", and \"serial\" you can read.";
    private static final String EXTRACT_USER_TEXT =
            "Extract the nameplate fields from this photo. Return ONLY the JSON object.";

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

    @Autowired AiVisionService visionService;
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
                .id(tenantId).slug("aivision-it-" + tenantId)
                .displayName("AiVision IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))  // non-zero so the budget gate passes
                .build()).block();
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    /** Stubs the Anthropic /v1/messages response whose single text block carries {@code inner}. */
    private void stubVision(String innerEscaped) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + innerEscaped + "\"}],"
                                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":30}}")));
    }

    @Test
    void classify_nonMoleTaxonomy_parsesAndSendsImageBlock() {
        String inner = "{\\\"classification\\\":\\\"hvac\\\",\\\"confidence\\\":0.88,"
                + "\\\"rationale\\\":\\\"condenser unit with fins\\\"}";
        stubVision(inner);

        VisionClassification result = visionService
                .classify(FAKE_IMAGE, "image/jpeg", MODEL, TRADE_SYSTEM_PROMPT, TRADE_USER_TEXT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.label()).isEqualTo("hvac");
        assertThat(result.confidence()).isEqualTo(0.88);
        assertThat(result.rationale()).contains("condenser");
        assertThat(result.hasLabel()).isTrue();

        // Proves the request went to WireMock (§7) and carried a base64 image block FIRST.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(WireMock.matchingJsonPath("$.messages[0].content[0].type",
                        WireMock.equalTo("image")))
                .withRequestBody(WireMock.matchingJsonPath("$.messages[0].content[0].source.type",
                        WireMock.equalTo("base64"))));
    }

    @Test
    void extract_openJson_returnsRawJsonNode() {
        String inner = "{\\\"make\\\":\\\"Carrier\\\",\\\"model\\\":\\\"59TP6\\\",\\\"serial\\\":\\\"X\\\"}";
        stubVision(inner);

        JsonNode result = visionService
                .extract(FAKE_IMAGE, "image/png", MODEL, EXTRACT_SYSTEM_PROMPT, EXTRACT_USER_TEXT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        assertThat(result).isNotNull();
        assertThat(result.path("make").asText()).isEqualTo("Carrier");
        assertThat(result.path("model").asText()).isEqualTo("59TP6");
        assertThat(result.path("serial").asText()).isEqualTo("X");
    }

    @Test
    void garbageNonJsonAnswer_classifyUnsure_extractEmptyNode() {
        stubVision("I cannot read anything useful from this photo, sorry.");

        VisionClassification classified = visionService
                .classify(FAKE_IMAGE, "image/jpeg", MODEL, TRADE_SYSTEM_PROMPT, TRADE_USER_TEXT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(classified).isNotNull();
        assertThat(classified.label()).isNull();
        assertThat(classified.confidence()).isEqualTo(0.0);
        assertThat(classified.hasLabel()).isFalse();

        // Same garbage answer → extract degrades to an empty object node.
        JsonNode extracted = visionService
                .extract(FAKE_IMAGE, "image/jpeg", MODEL, EXTRACT_SYSTEM_PROMPT, EXTRACT_USER_TEXT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(extracted).isNotNull();
        assertThat(extracted.isObject()).isTrue();
        assertThat(extracted.isEmpty()).isTrue();
    }

    @Test
    void blankImage_shortCircuits_noTraffic() {
        VisionClassification classified = visionService
                .classify(new byte[0], "image/jpeg", MODEL, TRADE_SYSTEM_PROMPT, TRADE_USER_TEXT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(classified).isNotNull();
        assertThat(classified.label()).isNull();
        assertThat(classified.confidence()).isEqualTo(0.0);

        JsonNode extracted = visionService
                .extract(null, "image/jpeg", MODEL, EXTRACT_SYSTEM_PROMPT, EXTRACT_USER_TEXT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(extracted).isNotNull();
        assertThat(extracted.isObject()).isTrue();
        assertThat(extracted.isEmpty()).isTrue();

        // No spend, no upstream traffic on either blank-image path.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void upstreamNon2xx_errors1202() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        StepVerifier.create(visionService
                        .classify(FAKE_IMAGE, "image/jpeg", MODEL, TRADE_SYSTEM_PROMPT, TRADE_USER_TEXT)
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1202);
                })
                .verify();
    }
}
