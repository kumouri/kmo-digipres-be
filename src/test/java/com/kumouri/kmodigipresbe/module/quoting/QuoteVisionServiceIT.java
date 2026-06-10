package com.kumouri.kmodigipresbe.module.quoting;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.quoting.model.AttributeSource;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteSynthesisService;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteVisionService;
import com.kumouri.kmodigipresbe.module.quoting.support.QuotingItStorageTestConfig;
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
 * T8 — {@link QuoteVisionService}: the Q2 vision auto-populate against WireMock Anthropic. Drives the
 * service directly under a synthetic widget {@code TenantContext} (the orchestrator establishes that
 * from the token in Q4). Proves the extracted attributes feed the price synthesis, the image content
 * block actually hits WireMock, the photo is always stored, and a vision failure degrades to an empty
 * attribute set (the manual/diagnostic path) without throwing — AI is triage, not truth.
 *
 * <h2>§7 no-live-external</h2>
 * The Anthropic vision call → WireMock via {@code @DynamicPropertySource}; {@code FileStorageService}
 * is the in-memory {@link QuotingItStorageTestConfig} stub; the Anthropic {@code apiKey} is a sandbox
 * fake. No live charge / upload anywhere.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, QuotingItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.quoting.vision-model=claude-sonnet-4-5"
})
class QuoteVisionServiceIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-quotenow-fake";
    private static final byte[] FAKE_IMAGE = "fake-jpeg-condenser-bytes".getBytes();

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
    @Autowired QuoteVisionService quoteVisionService;
    @Autowired QuoteSynthesisService quoteSynthesisService;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("quote-vision-it-" + tenantId)
                .displayName("Quote Vision IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("quoting"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new java.util.HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build())
                .contextWrite(TenantContextHolder.write(stamp()))
                .block();
    }

    private TenantContext stamp() {
        return new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
    }

    private void stubRead(String equipmentType, String brand, Integer age, String failure) {
        String ageJson = age == null ? "null" : age.toString();
        String inner = "{\\\"equipmentType\\\":\\\"" + equipmentType + "\\\",\\\"brand\\\":\\\"" + brand
                + "\\\",\\\"ageEstimateYears\\\":" + ageJson + ",\\\"visibleFailureMode\\\":\\\""
                + failure + "\\\"}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":40}}")));
    }

    private QuoteVisionService.VisionResult run() {
        return quoteVisionService.readFromPhoto(tenantId, FAKE_IMAGE, "image/jpeg", "condenser.jpg")
                .contextWrite(TenantContextHolder.write(stamp()))
                .block();
    }

    @Test
    void legibleRead_feedsAttributes_andSynthesisProducesARangeWithTheDisclaimer() {
        stubRead("condenser", "Carrier", 12, "blowing warm");
        PriceBook book = condenserBook();

        QuoteVisionService.VisionResult result = run();

        assertThat(result).isNotNull();
        assertThat(result.attachmentId()).isNotNull();
        QuoteAttributes attrs = result.attributes();
        assertThat(attrs.getSource()).isEqualTo(AttributeSource.VISION);
        assertThat(attrs.getEquipmentType()).isEqualTo("condenser");
        assertThat(attrs.getBrand()).isEqualTo("Carrier");
        assertThat(attrs.getAgeYears()).isEqualTo(12);
        assertThat(attrs.getFailureMode()).isEqualTo("blowing warm");
        // All four fields present → confidence 1.0.
        assertThat(attrs.getConfidence()).isEqualTo(1.0);

        // The read feeds the synthesis → a real (non-diagnostic) range, carrying the disclaimer.
        QuoteRange range = quoteSynthesisService.synthesize(book, attrs);
        assertThat(range.isDiagnosticOnly()).isFalse();
        assertThat(range.getLow()).isGreaterThan(BigDecimal.ZERO);
        assertThat(range.getEstimateDisclaimer())
                .isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER).isNotBlank();

        // The photo is stored as a QUOTE_REQUEST attachment under the tenant prefix.
        List<Attachment> atts = mongo.findAll(Attachment.class).collectList().block();
        assertThat(atts).hasSize(1);
        assertThat(atts.get(0).getSubjectType()).isEqualTo("QUOTE_REQUEST");
        assertThat(atts.get(0).getStorageRef()).startsWith("tenants/" + tenantId + "/");

        // The vision call hit WireMock WITH an image content block (§7 + plan test req).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].type", equalTo("image"))));
    }

    @Test
    void partialRead_lowersConfidence() {
        // Only equipmentType + age come back (brand + failureMode are JSON null) → confidence 0.5.
        String inner = "{\\\"equipmentType\\\":\\\"furnace\\\",\\\"brand\\\":null,"
                + "\\\"ageEstimateYears\\\":18,\\\"visibleFailureMode\\\":null}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":20}}")));
        QuoteVisionService.VisionResult result = run();
        QuoteAttributes attrs = result.attributes();
        assertThat(attrs.getEquipmentType()).isEqualTo("furnace");
        assertThat(attrs.getAgeYears()).isEqualTo(18);
        assertThat(attrs.getBrand()).isNull();
        assertThat(attrs.getFailureMode()).isNull();
        assertThat(attrs.getConfidence()).isEqualTo(0.5);
    }

    @Test
    void visionUpstreamFailure_degradesToEmptyAttributes_photoStillStored_neverThrows() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        QuoteVisionService.VisionResult result = run();

        assertThat(result).isNotNull();
        assertThat(result.attachmentId()).as("photo still stored on a vision failure").isNotNull();
        QuoteAttributes attrs = result.attributes();
        assertThat(attrs.isEmpty()).as("vision failure → empty attributes (manual path)").isTrue();
        assertThat(attrs.getSource()).isEqualTo(AttributeSource.VISION);
        assertThat(attrs.getConfidence()).isEqualTo(0.0);

        // Synthesis on empty attributes degrades to a diagnostic range — the quote is still produced.
        QuoteRange range = quoteSynthesisService.synthesize(condenserBook(), attrs);
        assertThat(range.isDiagnosticOnly()).isTrue();
        assertThat(range.getEstimateDisclaimer()).isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER);

        assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1);
    }

    @Test
    void blankRead_emptyObject_yieldsEmptyAttributes() {
        // The model returns a well-formed all-null object → nothing legible.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"{}\"}],"
                                + "\"usage\":{\"input_tokens\":10,\"output_tokens\":2}}")));

        QuoteVisionService.VisionResult result = run();
        assertThat(result.attributes().isEmpty()).isTrue();
        assertThat(result.attachmentId()).isNotNull();
    }

    private PriceBook condenserBook() {
        return PriceBook.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).currency("USD")
                .lineItems(List.of(
                        com.kumouri.kmodigipresbe.module.quoting.model.PriceBookLineItem.builder()
                                .equipmentType("condenser")
                                .jobKind(com.kumouri.kmodigipresbe.module.quoting.model.JobKind.REPAIR)
                                .low(new BigDecimal("250")).high(new BigDecimal("1500"))
                                .build()))
                .diagnosticVisitLow(new BigDecimal("89"))
                .diagnosticVisitHigh(new BigDecimal("149"))
                .build();
    }
}
