package com.kumouri.kmodigipresbe.module.styleconsult;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributes;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultVisionService;
import com.kumouri.kmodigipresbe.module.styleconsult.support.StyleConsultItStorageTestConfig;
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
import java.util.HashMap;
import java.util.List;
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
 * T9 — {@link StyleConsultVisionService}: the S1 inspiration-photo vision read against WireMock
 * Anthropic. Drives the service directly under a synthetic widget {@code TenantContext} (the
 * orchestrator establishes that from the token in the public path). Proves the extracted style
 * attributes parse, the image content block actually hits WireMock, the photo is always stored, and a
 * vision failure degrades to an empty attribute set (the manual path) without throwing — AI is triage,
 * not truth.
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic vision call → WireMock via {@code @DynamicPropertySource}; {@code FileStorageService}
 * is the in-memory {@link StyleConsultItStorageTestConfig} stub; the Anthropic {@code apiKey} is a
 * sandbox fake. No live charge / upload anywhere.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, StyleConsultItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.styleconsult.vision-model=claude-sonnet-4-5"
})
class StyleConsultVisionServiceIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-styleconsult-fake";
    private static final byte[] FAKE_IMAGE = "fake-jpeg-balayage-inspo-bytes".getBytes();

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

    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired StyleConsultVisionService styleConsultVisionService;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("styleconsult-vision-it-" + tenantId)
                .displayName("StyleConsult Vision IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build())
                .contextWrite(TenantContextHolder.write(stamp()))
                .block();
    }

    private TenantContext stamp() {
        return new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
    }

    private void stubRead(String style, String length, String texture, String color) {
        String inner = "{\\\"styleCategory\\\":\\\"" + style + "\\\",\\\"length\\\":\\\"" + length
                + "\\\",\\\"texture\\\":\\\"" + texture + "\\\",\\\"color\\\":\\\"" + color + "\\\"}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":40}}")));
    }

    private StyleConsultVisionService.VisionResult run() {
        return styleConsultVisionService.readFromPhoto(tenantId, FAKE_IMAGE, "image/jpeg", "inspo.jpg")
                .contextWrite(TenantContextHolder.write(stamp()))
                .block();
    }

    @Test
    void legibleRead_parsesAttributes_imageBlockHitsWireMock_photoStored() {
        stubRead("balayage", "long", "wavy", "blonde");

        StyleConsultVisionService.VisionResult result = run();

        assertThat(result).isNotNull();
        assertThat(result.attachmentId()).isNotNull();
        StyleAttributes attrs = result.attributes();
        assertThat(attrs.getSource()).isEqualTo(StyleAttributeSource.VISION);
        assertThat(attrs.getStyleCategory()).isEqualTo("balayage");
        assertThat(attrs.getLength()).isEqualTo("long");
        assertThat(attrs.getTexture()).isEqualTo("wavy");
        assertThat(attrs.getColor()).isEqualTo("blonde");
        // All four fields present → confidence 1.0.
        assertThat(attrs.getConfidence()).isEqualTo(1.0);

        // The photo is stored as a STYLE_CONSULT attachment under the tenant prefix.
        List<Attachment> atts = mongo.findAll(Attachment.class).collectList().block();
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).getSubjectType()).isEqualTo("STYLE_CONSULT");
        assertThat(atts.get(0).getStorageRef()).startsWith("tenants/" + tenantId + "/");

        // The vision call hit WireMock WITH an image content block (§7 + plan test req).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].type", equalTo("image"))));
    }

    @Test
    void partialRead_lowersConfidence() {
        // Only styleCategory + color come back (length + texture are JSON null) → confidence 0.5.
        String inner = "{\\\"styleCategory\\\":\\\"highlights\\\",\\\"length\\\":null,"
                + "\\\"texture\\\":null,\\\"color\\\":\\\"blonde\\\"}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":20}}")));

        StyleConsultVisionService.VisionResult result = run();
        StyleAttributes attrs = result.attributes();
        assertThat(attrs.getStyleCategory()).isEqualTo("highlights");
        assertThat(attrs.getColor()).isEqualTo("blonde");
        assertThat(attrs.getLength()).isNull();
        assertThat(attrs.getTexture()).isNull();
        assertThat(attrs.getConfidence()).isEqualTo(0.5);
    }

    @Test
    void visionUpstreamFailure_degradesToEmptyAttributes_photoStillStored_neverThrows() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        StyleConsultVisionService.VisionResult result = run();

        assertThat(result).isNotNull();
        assertThat(result.attachmentId()).as("photo still stored on a vision failure").isNotNull();
        StyleAttributes attrs = result.attributes();
        assertThat(attrs.isEmpty()).as("vision failure → empty attributes (manual path)").isTrue();
        assertThat(attrs.getSource()).isEqualTo(StyleAttributeSource.VISION);
        assertThat(attrs.getConfidence()).isEqualTo(0.0);
        assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1);
    }

    @Test
    void blankRead_emptyObject_yieldsEmptyAttributes() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],"
                                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}")));

        StyleConsultVisionService.VisionResult result = run();
        assertThat(result.attributes().isEmpty()).isTrue();
        assertThat(result.attachmentId()).isNotNull();
    }
}
