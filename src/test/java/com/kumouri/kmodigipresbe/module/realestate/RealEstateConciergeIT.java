package com.kumouri.kmodigipresbe.module.realestate;

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
import com.kumouri.kmodigipresbe.module.realestate.service.ListingDisclosureService;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
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
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Estate Concierge RE-1 — the crux IT: disclosure-text indexing, listing-scoped RAG retrieval, the
 * strict no-hallucination grounding (HANDOFF), citation persistence, the {@code smsMode="realestate"}
 * inbound seam, and conversation state.
 *
 * <h2>How RAG is made deterministic (no Atlas / no OpenAI / no Anthropic)</h2>
 * <ul>
 *   <li><strong>{@link VectorIndex}</strong> → an in-memory {@link InMemoryVectorIndex} ({@code @Primary},
 *       overrides {@code MongoAtlasVectorIndex}): {@code upsert} stores (tenant, sourceType, sourceId,
 *       metadata); {@code search} returns ALL of the tenant's stored hits — so the production
 *       {@code RagRetrievalService.retrieveForListing} filters ({@code sourceType=="ListingDisclosure"} +
 *       {@code matchesListing} on {@code listingId} metadata) do the real scoping. This lets the test
 *       exercise the genuine retrieval + filter code path without a live vector engine.</li>
 *   <li><strong>{@code EmbeddingService}</strong> → the shared mock from {@link TestcontainersConfiguration}
 *       (returns a zero vector — the in-memory index ignores the vector for matching).</li>
 *   <li><strong>Anthropic</strong> → WireMock ({@code kmosf.ai.anthropic.base-url}); a canned grounded
 *       answer for the matching question, the {@code HANDOFF} token for the unsupported one.</li>
 *   <li><strong>{@link TwilioSmsService}</strong> → a {@code @MockitoBean} capture seam (its base URL is
 *       not config-driven — the GapFillWaitlistIT precedent).</li>
 * </ul>
 *
 * <h2>Coverage (plan RE-1 testability note)</h2>
 * <ol>
 *   <li>(d) disclosure-text indexing stamps {@code listingId} metadata as source type "ListingDisclosure";</li>
 *   <li>(a) a listing-scoped question with a matching disclosure → a grounded answer with the citation
 *       persisted (correct {@code disclosureId}/{@code disclosureType}/{@code contentPreview});</li>
 *   <li>(b1) a question with NO matching chunk → HANDOFF and the model is NEVER called (no fabrication);</li>
 *   <li>(b2) chunks present but the model replies HANDOFF → handoff (no fabricated answer);</li>
 *   <li>(c) cross-listing isolation — listing A's question never retrieves listing B's chunk;</li>
 *   <li>inbound: a signed realestate-mode Twilio POST → a grounded answer SMS + a persisted conversation;</li>
 *   <li>inbound: a bad signature → 401/4000, zero effect.</li>
 * </ol>
 * (Hard gate (e) — ChairFill YES/STOP byte-identical with smsMode unset — is the unchanged
 * {@code GapFillWaitlistIT}, re-run as a regression gate.)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, RealEstateConciergeIT.InMemoryVectorIndexConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true",
        "kmosf.realestate.concierge-model=claude-haiku-4-5",
        "kmosf.realestate.retrieval-top-k=12"
})
class RealEstateConciergeIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_re1";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-re1-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String TRACKED_NUMBER_A = "+16185559001";
    private static final String TRACKED_NUMBER_C = "+16185559003";
    private static final String BUYER = "+16185550200";

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

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Listing.class).block();
        mongo.remove(new Query(), ListingDisclosure.class).block();
        mongo.remove(new Query(), ConciergeConversation.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        vectorIndex.clear();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("realestate"));
        seedAnthropic(tenantId);
        seedTwilio(tenantId);
    }

    private TenantContext ctx() {
        return new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    // ── (d) disclosure-text indexing stamps listingId metadata ───────────────────

    @Test
    void disclosureIndexing_stampsListingIdMetadata_asListingDisclosureSourceType() {
        Listing listing = createListing(TRACKED_NUMBER_A, "123 Oak St");
        ListingDisclosure disclosure = createDisclosure(listing.getId(), DisclosureType.ROOF,
                "Roof replaced 2019, architectural shingles, transferable warranty.");

        // The disclosure was indexed (indexedAt stamped) ...
        assertThat(disclosure.getIndexedAt()).as("disclosure indexedAt stamped after embed+upsert")
                .isNotNull();

        // ... as source type "ListingDisclosure" carrying listingId metadata (the §3 contract).
        InMemoryVectorIndex.Stored stored = vectorIndex.get(tenantId,
                RagRetrievalService.LISTING_DISCLOSURE_SOURCE_TYPE, disclosure.getId());
        assertThat(stored).as("vector upserted as ListingDisclosure").isNotNull();
        assertThat(stored.metadata().get("listingId")).isEqualTo(listing.getId().toString());
        assertThat(stored.metadata().get("disclosureType")).isEqualTo("ROOF");
        assertThat(stored.metadata().get("contentPreview").toString()).contains("Roof replaced 2019");
    }

    // ── (a) + (c) grounded answer with citation, no cross-listing bleed ──────────

    @Test
    void groundedQuestion_returnsCitedAnswer_andNeverRetrievesOtherListingsChunk() {
        Listing listingA = createListing(TRACKED_NUMBER_A, "123 Oak St");
        ListingDisclosure roofA = createDisclosure(listingA.getId(), DisclosureType.ROOF,
                "Roof replaced 2019, architectural shingles, transferable warranty.");

        // A DIFFERENT listing B with its own (different) roof disclosure — must never bleed into A.
        Listing listingB = createListing("+16185559002", "456 Pine Ave");
        ListingDisclosure roofB = createDisclosure(listingB.getId(), DisclosureType.ROOF,
                "Roof is original 1978, no records of replacement.");

        stubGroundedAnswer("The roof was replaced in 2019 (architectural shingles) with a transferable "
                + "warranty.");

        ConciergeConversation conv = inboundQuestion(TRACKED_NUMBER_A, "how old is the roof?");

        // A grounded ASSISTANT turn with the answer + a citation to LISTING A's roof disclosure only.
        ConciergeTurn assistant = lastAssistantTurn(conv);
        assertThat(assistant.isHandoff()).isFalse();
        assertThat(assistant.getBody()).contains("2019");
        assertThat(assistant.getCitations()).hasSize(1);
        ConciergeTurn.TurnCitation cite = assistant.getCitations().get(0);
        assertThat(cite.getDisclosureId()).as("cites listing A's disclosure").isEqualTo(roofA.getId());
        assertThat(cite.getDisclosureId()).as("never cites listing B").isNotEqualTo(roofB.getId());
        assertThat(cite.getDisclosureType()).isEqualTo("ROOF");
        assertThat(cite.getContentPreview()).contains("Roof replaced 2019");

        // The grounded answer was texted back to the buyer.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("2019");
        assertThat(sentSms.get(0).to().e164()).isEqualTo(BUYER);
    }

    // ── (b1) no matching chunk → HANDOFF, model NEVER called (no fabrication) ────

    @Test
    void unknowableQuestion_noChunks_handsOff_withoutCallingModel() {
        // Listing C has NO disclosures indexed at all → retrieval returns zero chunks for C.
        Listing listingC = createListing(TRACKED_NUMBER_C, "789 Elm Rd");
        // (a sibling listing with a septic disclosure exists, to prove C still can't borrow it)
        Listing other = createListing("+16185559004", "1 Other Way");
        createDisclosure(other.getId(), DisclosureType.GENERAL,
                "Property is on a private septic system, pumped 2022.");
        stubGroundedAnswer("(this canned answer must NEVER be sent — there is nothing to ground on)");

        ConciergeConversation conv = inboundQuestion(TRACKED_NUMBER_C, "is there a septic system?");

        ConciergeTurn assistant = lastAssistantTurn(conv);
        assertThat(assistant.isHandoff()).as("no chunks → HANDOFF").isTrue();
        assertThat(assistant.getCitations()).isEmpty();

        // The model was NEVER called (the no-chunks short-circuit) — the hard no-hallucination guarantee.
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));

        // The buyer got the graceful handoff line, not the canned fabricated answer.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body().toLowerCase()).contains("agent");
        assertThat(sentSms.get(0).body()).doesNotContain("septic");
    }

    // ── (b2) chunks present but model replies HANDOFF → handoff, no fabrication ──

    @Test
    void modelRepliesHandoffToken_handsOff_noFabricatedAnswer() {
        Listing listingA = createListing(TRACKED_NUMBER_A, "123 Oak St");
        // A chunk exists (so the model IS called) but it doesn't answer the question → model returns HANDOFF.
        createDisclosure(listingA.getId(), DisclosureType.BASEMENT,
                "Basement: finished 2021, egress window, no known moisture.");
        stubExactAnswer("HANDOFF");

        ConciergeConversation conv = inboundQuestion(TRACKED_NUMBER_A, "what is the property tax rate?");

        ConciergeTurn assistant = lastAssistantTurn(conv);
        assertThat(assistant.isHandoff()).as("model HANDOFF token → handoff").isTrue();
        assertThat(assistant.getCitations()).isEmpty();
        // The model WAS called this time (chunks were present), but produced no fabricated answer.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body().toLowerCase()).contains("agent");
    }

    // ── (BE-12) Fair-Housing-flagged AI answer is suppressed + handed off ─────────

    @Test
    void fairHousingFlaggedAnswer_isSuppressed_andHandedOff() {
        Listing listingA = createListing(TRACKED_NUMBER_A, "123 Oak St");
        // A chunk exists so the model IS called; the model (e.g. steered by a crafted buyer text)
        // returns an answer containing Fair-Housing steering language.
        createDisclosure(listingA.getId(), DisclosureType.GENERAL,
                "The neighborhood has a community pool and is close to several schools.");
        stubGroundedAnswer("This is a safe neighborhood, perfect for families and great for kids.");

        ConciergeConversation conv = inboundQuestion(TRACKED_NUMBER_A,
                "tell me about the area for my family");

        ConciergeTurn assistant = lastAssistantTurn(conv);
        // The steering answer must NOT be sent — the turn is forced to a safe HANDOFF.
        assertThat(assistant.isHandoff()).as("Fair-Housing-flagged answer → HANDOFF").isTrue();
        assertThat(assistant.getBody().toLowerCase()).contains("agent");
        assertThat(assistant.getBody().toLowerCase()).doesNotContain("safe neighborhood");
        assertThat(assistant.getBody().toLowerCase()).doesNotContain("perfect for families");

        // The buyer received the handoff line, never the steering text.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body().toLowerCase()).contains("agent");
        assertThat(sentSms.get(0).body().toLowerCase()).doesNotContain("safe neighborhood");
    }

    // ── inbound: bad signature → 401/4000, zero effect ───────────────────────────

    @Test
    void inboundSms_badSignature_401_4000_zeroEffect() {
        createListing(TRACKED_NUMBER_A, "123 Oak St");

        String path = "/public/integrations/twilio/" + tenantId + "/sms";
        MultiValueMap<String, String> form = smsForm(BUYER, TRACKED_NUMBER_A, "how old is the roof?");
        web.post().uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", "deadbeef_invalid")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4000);

        // Zero effect — no conversation, no SMS, model never called.
        assertThat(mongo.findAll(ConciergeConversation.class).collectList().block()).isEmpty();
        assertThat(sentSms).isEmpty();
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Drives a signed realestate-mode inbound Twilio SMS over the reused webhook and returns the saved conversation. */
    private ConciergeConversation inboundQuestion(String trackedNumber, String body) {
        postSms(BUYER, trackedNumber, body).expectStatus().isOk();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<ConciergeConversation> all = mongo.findAll(ConciergeConversation.class).collectList().block();
            assertThat(all).hasSize(1);
            // an assistant turn has been appended (answer or handoff)
            assertThat(all.get(0).getTurns().stream()
                    .anyMatch(t -> t.getRole() == ConciergeTurn.Role.ASSISTANT)).isTrue();
        });
        return mongo.findAll(ConciergeConversation.class).collectList().block().get(0);
    }

    private static ConciergeTurn lastAssistantTurn(ConciergeConversation conv) {
        return conv.getTurns().stream()
                .filter(t -> t.getRole() == ConciergeTurn.Role.ASSISTANT)
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private Listing createListing(String trackedPhone, String address) {
        return listingService.create(Listing.builder()
                        .addressLine(address).city("St. Louis").state("MO").zip("63101")
                        .trackedPhone(trackedPhone)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    private ListingDisclosure createDisclosure(UUID listingId, DisclosureType type, String text) {
        return disclosureService.create(listingId, ListingDisclosure.builder()
                        .disclosureType(type).text(text).build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    private WebTestClient.ResponseSpec postSms(String from, String to, String body) {
        String path = "/public/integrations/twilio/" + tenantId + "/sms";
        MultiValueMap<String, String> form = smsForm(from, to, body);
        String sig = sign(fullUrl(path), form);
        return web.post().uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange();
    }

    private MultiValueMap<String, String> smsForm(String from, String to, String body) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("MessageSid", "SM_test_" + UUID.randomUUID());
        form.add("From", from);
        form.add("To", to);
        form.add("Body", body);
        return form;
    }

    private String fullUrl(String path) {
        return "https://" + FORWARDED_HOST + path;
    }

    private String sign(String fullUrl, MultiValueMap<String, String> form) {
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

    /** A canned Anthropic answer (used as the grounded reply). */
    private void stubGroundedAnswer(String text) {
        stubExactAnswer(text);
    }

    private void stubExactAnswer(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}")));
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        mongo.save(Tenant.builder()
                .id(tid).slug("re1-" + tid)
                .displayName("Gateway Realty RE-1 IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    private void seedAnthropic(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    /** Twilio connection in realestate SMS mode (config.smsMode="realestate"). */
    private void seedTwilio(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_re1",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", TRACKED_NUMBER_A)))
                .config(new HashMap<>(Map.of("smsMode", "realestate")))
                .build()).block();
    }

    // ── deterministic in-memory VectorIndex ──────────────────────────────────────

    /**
     * {@code @Primary} in-memory {@link VectorIndex} so the test exercises the real
     * {@code RagRetrievalService.retrieveForListing} filters without a live Atlas vector engine. Stores
     * upserts; {@code search} returns ALL of the tenant's hits (the listing/source-type filter narrows).
     */
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

        Stored get(UUID tenantId, String sourceType, UUID sourceId) {
            return store.get(new Key(tenantId, sourceType, sourceId));
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
            // Return all of this tenant's stored vectors with a constant score; the production
            // RagRetrievalService.retrieveForListing applies the sourceType + listingId filters.
            return Flux.fromIterable(store.entrySet().stream()
                    .filter(e -> e.getKey().tenantId().equals(tenantId))
                    .map(e -> new VectorSearchHit(e.getValue().sourceType(), e.getValue().sourceId(),
                            0.9, e.getValue().metadata()))
                    .limit(topK)
                    .toList());
        }
    }
}
