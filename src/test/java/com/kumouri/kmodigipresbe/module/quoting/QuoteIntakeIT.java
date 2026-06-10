package com.kumouri.kmodigipresbe.module.quoting;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.quoting.controller.dto.QuoteResponse;
import com.kumouri.kmodigipresbe.module.quoting.model.JobKind;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBookLineItem;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.Recommendation;
import com.kumouri.kmodigipresbe.module.quoting.repository.PriceBookRepository;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteIntakeService;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteSynthesisService;
import com.kumouri.kmodigipresbe.module.quoting.support.QuotingItStorageTestConfig;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
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
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.BodyInserters;

import java.math.BigDecimal;
import java.time.Duration;
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
 * T8 — QuoteIntakeIT: the public homeowner intake headline ITs. POSTs to
 * {@code /public/integrations/quoting/{token}/quote} (multipart) and asserts the instant quote: a
 * photo read via the shared {@code AiVisionService.extract} feeds the price-book synthesis → a range
 * + a repair-vs-replace recommendation + <strong>the mandatory estimate disclaimer</strong>, the
 * QuoteRequest is persisted, the lead Contact is found-or-created. Proves the manual-only path, the
 * vision-failure → manual degrade, and the auth fences.
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic → WireMock via {@code @DynamicPropertySource}; {@code FileStorageService} = the in-memory
 * {@link QuotingItStorageTestConfig} stub; sandbox apiKey + a test widget-token secret.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, QuotingItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=quotenow-it-secret-0123456789",
        "kmosf.quoting.vision-model=claude-sonnet-4-5"
})
class QuoteIntakeIT {

    private static final String TEST_SECRET = "quotenow-it-secret-0123456789";
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

    @Autowired WebTestClient web;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired PriceBookRepository priceBooks;
    @Autowired PublicWidgetTokenService widgetTokens;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), QuoteRequest.class).block();
        mongo.remove(new Query(), PriceBook.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("quote-intake-it-" + tenantId)
                .displayName("Quote Intake IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("quoting")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).contextWrite(TenantContextHolder.write(ctx)).block();

        priceBooks.save(condenserBook()).contextWrite(TenantContextHolder.write(ctx)).block();
    }

    private PriceBook condenserBook() {
        return PriceBook.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).currency("USD")
                .lineItems(List.of(
                        PriceBookLineItem.builder()
                                .equipmentType("condenser").jobKind(JobKind.REPAIR)
                                .low(new BigDecimal("250")).high(new BigDecimal("1500"))
                                .typicalLifespanYears(15)
                                .agePerYearPct(new BigDecimal("3.0")).ageMaxPct(new BigDecimal("60"))
                                .build(),
                        PriceBookLineItem.builder()
                                .equipmentType("condenser").jobKind(JobKind.REPLACE)
                                .low(new BigDecimal("4500")).high(new BigDecimal("7000"))
                                .typicalLifespanYears(15)
                                .build()))
                .diagnosticVisitLow(new BigDecimal("89")).diagnosticVisitHigh(new BigDecimal("149"))
                .build();
    }

    private void stubRead(String equipmentType, String brand, Integer age, String failure) {
        String ageJson = age == null ? "null" : age.toString();
        String inner = "{\\\"equipmentType\\\":\\\"" + equipmentType + "\\\",\\\"brand\\\":\\\"" + brand
                + "\\\",\\\"ageEstimateYears\\\":" + ageJson + ",\\\"visibleFailureMode\\\":\\\""
                + failure + "\\\"}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":40}}")));
    }

    private String token() {
        return widgetTokens.issue(tenantId, QuoteIntakeService.WIDGET_TYPE, Duration.ofHours(1));
    }

    private WebTestClient.ResponseSpec postQuote(String token, byte[] image, String mediaType,
                                                 String filename, Map<String, String> textParts) {
        MultipartBodyBuilder builder = new MultipartBodyBuilder();
        if (image != null) {
            builder.part("image", new ByteArrayResource(image) {
                @Override
                public String getFilename() {
                    return filename;
                }
            }, mediaType == null ? null : MediaType.parseMediaType(mediaType));
        }
        if (textParts != null) {
            textParts.forEach(builder::part);
        }
        return web.post()
                .uri("/public/integrations/quoting/" + token + "/quote")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }

    @Test
    void photoIntake_oldUnit_replaceWithFinancing_disclaimerPresent_persisted() {
        stubRead("condenser", "Carrier", 12, "blowing warm");

        QuoteResponse resp = postQuote(token(), FAKE_IMAGE, "image/jpeg", "ac.jpg",
                Map.of("phone", "+13145551111", "problemDescription", "AC blowing warm"))
                .expectStatus().isOk()
                .expectBody(QuoteResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.quoteId()).isNotNull();
        assertThat(resp.equipmentType()).isEqualTo("condenser");
        assertThat(resp.recommendation()).isEqualTo(Recommendation.REPLACE);
        assertThat(resp.financingAvailable()).isTrue();
        assertThat(resp.low()).isGreaterThan(BigDecimal.ZERO);
        assertThat(resp.high()).isGreaterThan(resp.low());
        // The wrong-number-liability fence — the disclaimer is ALWAYS present.
        assertThat(resp.estimateDisclaimer())
                .isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER).isNotBlank();
        assertThat(resp.recommendationRationale()).isNotBlank();

        // Persisted QuoteRequest + a found-or-created lead Contact + the photo Attachment.
        List<QuoteRequest> saved = mongo.findAll(QuoteRequest.class).collectList().block();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getContactId()).isNotNull();
        assertThat(saved.get(0).getPhotoAttachmentId()).isNotNull();
        assertThat(mongo.findAll(Contact.class).collectList().block()).hasSize(1);
        assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1);

        // The vision call hit WireMock WITH an image content block.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content[0].type", equalTo("image"))));
    }

    @Test
    void manualOnlyIntake_noPhoto_stillProducesAQuote() {
        QuoteResponse resp = postQuote(token(), null, null, null,
                Map.of("equipmentType", "condenser", "ageYears", "4",
                        "failureMode", "low refrigerant", "phone", "+13145552222"))
                .expectStatus().isOk()
                .expectBody(QuoteResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.equipmentType()).isEqualTo("condenser");
        assertThat(resp.recommendation()).isEqualTo(Recommendation.REPAIR);
        assertThat(resp.estimateDisclaimer()).isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER);
        // No photo → no Attachment, but a quote + a contact.
        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(QuoteRequest.class).collectList().block()).hasSize(1);
        // No vision call at all on the manual path.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void visionFailure_degradesToManualAttributes_quoteStillProduced() {
        // Vision upstream 500, but the homeowner typed equipmentType + age → the quote still synthesizes.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        QuoteResponse resp = postQuote(token(), FAKE_IMAGE, "image/jpeg", "ac.jpg",
                Map.of("equipmentType", "condenser", "ageYears", "13", "phone", "+13145553333"))
                .expectStatus().isOk()
                .expectBody(QuoteResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.equipmentType()).isEqualTo("condenser");   // from the manual fallback
        assertThat(resp.recommendation()).isEqualTo(Recommendation.REPLACE); // 13yr → replace
        assertThat(resp.estimateDisclaimer()).isEqualTo(QuoteSynthesisService.ESTIMATE_DISCLAIMER);
        // The photo was still stored even though the read failed.
        assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1);
        assertThat(mongo.findAll(QuoteRequest.class).collectList().block()).hasSize(1);
    }

    @Test
    void missingPhoto_butManualAttrsPresent_200() {
        // Same as manual-only, asserting the "missing image part is NOT an error" contract.
        postQuote(token(), null, null, null,
                Map.of("equipmentType", "furnace", "ageYears", "5"))
                .expectStatus().isOk();
        assertThat(mongo.findAll(QuoteRequest.class).collectList().block()).hasSize(1);
    }

    @Test
    void unsupportedMediaType_415_4434() {
        postQuote(token(), "not-an-image".getBytes(), "application/pdf", "doc.pdf",
                Map.of("phone", "+13145554444"))
                .expectStatus().isEqualTo(415)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4434);
        assertThat(mongo.findAll(QuoteRequest.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void wrongWidgetTypeToken_401_4430_zeroEffect() {
        // A correctly-signed token but a non-"quote-intake" widgetType (a service-request token).
        String wrongType = widgetTokens.issue(tenantId, "service-request", Duration.ofHours(1));
        postQuote(wrongType, FAKE_IMAGE, "image/jpeg", "ac.jpg", Map.of("phone", "+1"))
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4430);
        assertThat(mongo.findAll(QuoteRequest.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
    }

    @Test
    void tamperedToken_401_zeroEffect() {
        String tampered = token().substring(0, token().length() - 4) + "XXXX";
        postQuote(tampered, FAKE_IMAGE, "image/jpeg", "ac.jpg", Map.of("phone", "+1"))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode")
                .value(o -> assertThat((Integer) o).isBetween(1600, 1699));
        assertThat(mongo.findAll(QuoteRequest.class).collectList().block()).isEmpty();
    }
}
