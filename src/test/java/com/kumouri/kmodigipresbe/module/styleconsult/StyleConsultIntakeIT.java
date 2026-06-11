package com.kumouri.kmodigipresbe.module.styleconsult;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.catalog.Product;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultResponse;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultService;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleRecommendationService;
import com.kumouri.kmodigipresbe.module.styleconsult.support.StyleConsultItStorageTestConfig;
import com.kumouri.kmodigipresbe.repository.ProductRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
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
 * T9 — StyleConsultIntakeIT: the public prospect intake headline ITs. POSTs to
 * {@code /public/integrations/styleconsult/{token}/consult} (multipart) and asserts the instant
 * consult: an inspiration photo read via the shared {@code AiVisionService.extract} feeds the
 * recommendation engine → matched services + <strong>margin-ranked retail</strong> +
 * <strong>the mandatory "your stylist will confirm" guardrail</strong>, the StyleConsult is persisted,
 * the lead Contact is found-or-created. Proves the manual-only path, the vision-failure → manual
 * degrade, and the auth fences.
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic → WireMock via {@code @DynamicPropertySource}; {@code FileStorageService} = the in-memory
 * {@link StyleConsultItStorageTestConfig} stub; sandbox apiKey + a test widget-token secret.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, StyleConsultItStorageTestConfig.class})
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=styleconsult-it-secret-0123456789",
        "kmosf.styleconsult.vision-model=claude-sonnet-4-5",
        "kmosf.styleconsult.max-services=3",
        "kmosf.styleconsult.max-products=3"
})
class StyleConsultIntakeIT {

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

    @Autowired WebTestClient web;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ServiceMenuRepository menus;
    @Autowired ProductRepository products;
    @Autowired PublicWidgetTokenService widgetTokens;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), StyleConsult.class).block();
        mongo.remove(new Query(), ServiceMenu.class).block();
        mongo.remove(new Query(), Product.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("styleconsult-it-" + tenantId)
                .displayName("StyleConsult IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).contextWrite(TenantContextHolder.write(ctx)).block();

        menus.save(serviceMenu()).contextWrite(TenantContextHolder.write(ctx)).block();
        products.saveAll(retailProducts()).contextWrite(TenantContextHolder.write(ctx))
                .collectList().block();
    }

    private ServiceMenu serviceMenu() {
        return ServiceMenu.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).name("menu")
                .services(List.of(
                        ServiceMenuItem.builder().id("svc-cut").name("Cut & Style")
                                .price(new BigDecimal("65")).durationMinutes(60).build(),
                        ServiceMenuItem.builder().id("svc-balayage").name("Balayage")
                                .price(new BigDecimal("185")).durationMinutes(180).build(),
                        ServiceMenuItem.builder().id("svc-gloss").name("Gloss & Tone")
                                .price(new BigDecimal("75")).durationMinutes(60).build()))
                .build();
    }

    private List<Product> retailProducts() {
        return List.of(
                retail("RET-LEAVEIN", "Leave-In Conditioner", "22", "8"),  // margin 14
                retail("RET-BOND", "Bond Builder", "38", "15"),            // margin 23 (highest)
                retail("RET-PURPLE", "Purple Shampoo", "28", "11"));       // margin 17
    }

    private Product retail(String sku, String name, String price, String cost) {
        return Product.builder().id(UUID.randomUUID()).tenantId(tenantId).sku(sku).name(name)
                .unitPrice(new BigDecimal(price)).unitCost(new BigDecimal(cost))
                .type(Product.ProductType.GOOD).active(true).build();
    }

    private void stubRead(String styleCategory, String length, String texture, String color) {
        String inner = "{\\\"styleCategory\\\":\\\"" + styleCategory + "\\\",\\\"length\\\":\\\""
                + length + "\\\",\\\"texture\\\":\\\"" + texture + "\\\",\\\"color\\\":\\\""
                + color + "\\\"}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"m\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + inner + "\"}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":40}}")));
    }

    private String token() {
        return widgetTokens.issue(tenantId, StyleConsultService.WIDGET_TYPE, Duration.ofHours(1));
    }

    private WebTestClient.ResponseSpec postConsult(String token, byte[] image, String mediaType,
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
                .uri("/public/integrations/styleconsult/" + token + "/consult")
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(BodyInserters.fromMultipartData(builder.build()))
                .exchange();
    }

    @Test
    void photoIntake_balayage_serviceAndMarginRankedRetail_guardrail_persisted() {
        stubRead("balayage", "long", "wavy", "blonde");

        StyleConsultResponse resp = postConsult(token(), FAKE_IMAGE, "image/jpeg", "inspo.jpg",
                Map.of("phone", "+13125551111", "notes", "wedding guest, low maintenance"))
                .expectStatus().isOk()
                .expectBody(StyleConsultResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.consultId()).isNotNull();
        assertThat(resp.styleCategory()).isEqualTo("balayage");
        assertThat(resp.attributeSource()).isEqualTo(StyleAttributeSource.VISION);

        // Services recommended; balayage surfaces (keyword match).
        assertThat(resp.serviceRecommendations()).isNotEmpty();
        assertThat(resp.serviceRecommendations()).extracting("name").contains("Balayage");

        // Retail is still margin-ranked (server-side): Bond Builder (23) > Purple Shampoo (17) >
        // Leave-In (14) — the ORDER proves the ranking. The prospect-safe view no longer exposes the
        // marginAmount/cost itself (security fix AI-03 — see noCostOrMarginLeakedToProspect_AI03).
        assertThat(resp.retailRecommendations()).extracting("name")
                .containsExactly("Bond Builder", "Purple Shampoo", "Leave-In Conditioner");
        assertThat(resp.retailRecommendations().get(0).price()).isEqualByComparingTo("38");

        // The never-auto-charge guardrail is on EVERY recommendation.
        assertThat(resp.serviceRecommendations()).allSatisfy(s ->
                assertThat(s.getRationale()).contains(StyleRecommendationService.STYLIST_CONFIRM_NOTE));
        assertThat(resp.retailRecommendations()).allSatisfy(r ->
                assertThat(r.rationale()).contains(StyleRecommendationService.STYLIST_CONFIRM_NOTE));

        // Persisted StyleConsult + a found-or-created lead Contact + the photo Attachment.
        List<StyleConsult> saved = mongo.findAll(StyleConsult.class).collectList().block();
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
    void noCostOrMarginLeakedToProspect_AI03() {
        // Security fix AI-03: the PUBLIC consult response must not serialize the salon's wholesale
        // unit cost or per-product margin. Assert the prospect-safe fields remain (name/sku/price/
        // rationale) AND that cost/marginAmount are absent from every retail rec.
        stubRead("balayage", "long", "wavy", "blonde");

        byte[] raw = postConsult(token(), FAKE_IMAGE, "image/jpeg", "inspo.jpg",
                Map.of("phone", "+13125559999"))
                .expectStatus().isOk()
                .expectBody()
                // The margin-ranked retail line is present (the feature still works) …
                .jsonPath("$.retailRecommendations[0].name").isEqualTo("Bond Builder")
                .jsonPath("$.retailRecommendations[0].price").exists()
                .jsonPath("$.retailRecommendations[0].sku").isEqualTo("RET-BOND")
                .jsonPath("$.retailRecommendations[0].rationale").exists()
                // … but the salon-confidential cost + margin are GONE from the retail recs.
                .jsonPath("$.retailRecommendations[0].cost").doesNotExist()
                .jsonPath("$.retailRecommendations[0].marginAmount").doesNotExist()
                .jsonPath("$.retailRecommendations[1].cost").doesNotExist()
                .jsonPath("$.retailRecommendations[1].marginAmount").doesNotExist()
                .returnResult().getResponseBody();

        // Belt-and-suspenders: the literal field names appear nowhere in the serialized body.
        String body = raw == null ? "" : new String(raw);
        assertThat(body).doesNotContain("marginAmount");
        assertThat(body).doesNotContain("\"cost\"");
    }

    @Test
    void manualOnlyIntake_noPhoto_stillProducesAConsult() {
        StyleConsultResponse resp = postConsult(token(), null, null, null,
                Map.of("styleCategory", "balayage", "phone", "+13125552222"))
                .expectStatus().isOk()
                .expectBody(StyleConsultResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.styleCategory()).isEqualTo("balayage");
        assertThat(resp.attributeSource()).isEqualTo(StyleAttributeSource.MANUAL);
        assertThat(resp.retailRecommendations()).extracting("name")
                .containsExactly("Bond Builder", "Purple Shampoo", "Leave-In Conditioner");
        // No photo → no Attachment, but a consult + a contact.
        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(StyleConsult.class).collectList().block()).hasSize(1);
        // No vision call at all on the manual path.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void visionFailure_degradesToManualAttributes_consultStillProduced() {
        // Vision upstream 500, but the prospect typed a style → recommendations still compose.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        StyleConsultResponse resp = postConsult(token(), FAKE_IMAGE, "image/jpeg", "inspo.jpg",
                Map.of("styleCategory", "highlights", "phone", "+13125553333"))
                .expectStatus().isOk()
                .expectBody(StyleConsultResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.styleCategory()).isEqualTo("highlights"); // from the manual fallback
        assertThat(resp.retailRecommendations()).isNotEmpty();
        // The photo was still stored even though the read failed.
        assertThat(mongo.findAll(Attachment.class).collectList().block()).hasSize(1);
        assertThat(mongo.findAll(StyleConsult.class).collectList().block()).hasSize(1);
    }

    @Test
    void unsupportedMediaType_415_4454() {
        postConsult(token(), "not-an-image".getBytes(), "application/pdf", "doc.pdf",
                Map.of("phone", "+13125554444"))
                .expectStatus().isEqualTo(415)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4454);
        assertThat(mongo.findAll(StyleConsult.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void oversizedImage_413_4454_zeroEffect() {
        // Security fix AI-02 — one byte over the controller's MAX_IMAGE_BYTES cap (15 MB) aborts the
        // bounded DataBufferUtils.join early → 413, not an OOM. See StyleConsultIntakeController.MAX_IMAGE_BYTES.
        byte[] tooBig = new byte[15 * 1024 * 1024 + 1];
        postConsult(token(), tooBig, "image/jpeg", "huge.jpg", Map.of("phone", "+13125557777"))
                .expectStatus().isEqualTo(413)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4454);
        assertThat(mongo.findAll(StyleConsult.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
        assertThat(wireMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void wrongWidgetTypeToken_401_4450_zeroEffect() {
        // A correctly-signed token but a non-"style-consult" widgetType (a quote-intake token).
        String wrongType = widgetTokens.issue(tenantId, "quote-intake", Duration.ofHours(1));
        postConsult(wrongType, FAKE_IMAGE, "image/jpeg", "inspo.jpg", Map.of("phone", "+1"))
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4450);
        assertThat(mongo.findAll(StyleConsult.class).collectList().block()).isEmpty();
        assertThat(mongo.findAll(Attachment.class).collectList().block()).isEmpty();
    }

    @Test
    void tamperedToken_401_zeroEffect() {
        String tampered = token().substring(0, token().length() - 4) + "XXXX";
        postConsult(tampered, FAKE_IMAGE, "image/jpeg", "inspo.jpg", Map.of("phone", "+1"))
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode")
                .value(o -> assertThat((Integer) o).isBetween(1600, 1699));
        assertThat(mongo.findAll(StyleConsult.class).collectList().block()).isEmpty();
    }
}
