package com.kumouri.kmodigipresbe.module.realestate;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.realestate.marketing.ListingMarketingService;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhoto;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Estate Concierge RE-4 — the Marketing Studio: vision-captioned listing photos + Sonnet-drafted MLS
 * remarks / social captions / email blast, the Fair-Housing lint, and the draft → approve / skip queue
 * (NEVER auto-published).
 *
 * <h2>How it is made deterministic (no live Anthropic / OpenAI / Atlas / S3)</h2>
 * <ul>
 *   <li><strong>Anthropic</strong> → WireMock ({@code kmosf.ai.anthropic.base-url}). BOTH the vision
 *       {@code extract} (per photo) and the text generation POST to {@code /}; they are disambiguated by
 *       request body — the vision call carries an {@code image} content block
 *       ({@code messages[0].content[0].type=="image"}), the text call carries a plain string content. Two
 *       priority stubs route each to its canned JSON.</li>
 *   <li><strong>{@link FileStorageService}</strong> → an in-memory {@code @Primary} stub that actually
 *       stores + returns the photo bytes (so {@code getBytes} feeds the vision call); the RE-4 read-twin of
 *       the {@code putBytes} stub the mole/equipment ITs use.</li>
 *   <li><strong>{@link DomainEventPublisher}</strong> → the real publisher; a subscriber captures the
 *       {@code LISTING_MARKETING_DRAFTED} / {@code LISTING_MARKETING_APPROVED} events.</li>
 * </ul>
 *
 * <h2>Coverage (the RE-4 hard gates)</h2>
 * <ol>
 *   <li>generate → a DRAFTED draft with MLS remarks + 3 social captions + an email blast + per-photo
 *       (stubbed-vision) callouts; {@code LISTING_MARKETING_DRAFTED} emitted;</li>
 *   <li>the Fair-Housing lint flags a banned term (the text stub returns steering language) → the draft is
 *       saved DRAFTED/FLAGGED (never blocked, never auto-published);</li>
 *   <li>approve → APPROVED + leaves the DRAFTED queue; {@code LISTING_MARKETING_APPROVED} emitted;</li>
 *   <li>a Claude (text) failure → a best-effort partial/empty DRAFTED draft + degraded flag (no error);</li>
 *   <li>never-auto-publish — generate alone leaves the draft DRAFTED (no APPROVED/published state).</li>
 * </ol>
 * The RE-1 grounding, RE-2 qualification, RE-3 booking, ChairFill inbound, and the scorer ITs stay green as
 * regression gates.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RealEstateMarketingStudioIT.InMemoryStorageConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true",
        "kmosf.realestate.marketing-model=claude-sonnet-4-6",
        "kmosf.realestate.marketing-vision-model=claude-sonnet-4-5"
})
class RealEstateMarketingStudioIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-re4-fake";
    private static final byte[] FAKE_IMAGE = "fake-listing-photo-bytes".getBytes();

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

    @Autowired ListingService listingService;
    @Autowired ListingMarketingService marketingService;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    private List<DomainEvent> observed;
    private reactor.core.Disposable eventSub;
    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Listing.class).block();
        mongo.remove(new Query(), ListingPhoto.class).block();
        mongo.remove(new Query(), ListingMarketingDraft.class).block();
        mongo.remove(new Query(), Attachment.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        observed = new CopyOnWriteArrayList<>();
        eventSub = eventPublisher.stream().subscribe(observed::add);

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("realestate"));
        seedAnthropic(tenantId);
    }

    @AfterEach
    void disposeSub() {
        if (eventSub != null) {
            eventSub.dispose();
        }
    }

    private TenantContext ctx() {
        return new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    // ── (1) generate → DRAFTED draft: MLS remarks + 3 captions + email + photo callouts ──

    @Test
    void generate_producesDraftedPackage_withCaptionsAndPhotoCallouts_emitsEvent() {
        Listing listing = createListing("123 Oak St");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(cleanPackageJson());

        ListingMarketingDraft draft = generate(listing.getId());

        // The draft is DRAFTED (never auto-published).
        assertThat(draft.getStatus()).isEqualTo(ListingMarketingDraft.Status.DRAFTED);
        assertThat(draft.getApprovedAt()).isNull();
        assertThat(draft.isGenerationDegraded()).isFalse();

        // MLS remarks + 3 social captions + email blast = all 5 channels present.
        assertThat(draft.getPieces()).hasSize(5);
        assertThat(channelText(draft, MarketingChannel.MLS_REMARKS)).contains("charming");
        assertThat(channelText(draft, MarketingChannel.INSTAGRAM)).contains("#");
        assertThat(channelText(draft, MarketingChannel.FACEBOOK)).isNotBlank();
        assertThat(channelText(draft, MarketingChannel.X)).isNotBlank();
        assertThat(channelText(draft, MarketingChannel.EMAIL_BLAST)).contains("Hello");

        // The listing photo was captioned by the (stubbed) vision call → a per-photo callout.
        assertThat(draft.getPhotoCaptions()).hasSize(1);
        ListingMarketingDraft.PhotoCaption cap = draft.getPhotoCaptions().get(0);
        assertThat(cap.getCaption()).contains("kitchen");
        assertThat(cap.getFeatures()).contains("granite countertops");

        // A clean package has no Fair-Housing flags.
        assertThat(draft.isFairHousingFlagged()).isFalse();
        assertThat(draft.getFairHousingFlags()).isEmpty();

        // It is queued for the agent (DRAFTED list) and the event fired.
        List<ListingMarketingDraft> queue = marketingService.listDrafted()
                .contextWrite(TenantContextHolder.write(ctx())).collectList().block();
        assertThat(queue).extracting(ListingMarketingDraft::getId).contains(draft.getId());

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(draftedEvents()).hasSize(1));
        assertThat(draftedEvents().get(0).payload().get("listingId")).isEqualTo(listing.getId().toString());
    }

    // ── (2) Fair-Housing lint flags a banned term → DRAFTED/FLAGGED, never blocked ──

    @Test
    void steeringLanguage_isFlaggedByLint_butDraftStillSavedDraftedNotBlocked() {
        Listing listing = createListing("456 Pine Ave");
        addPhoto(listing.getId());
        stubVisionCaption();
        // The text stub returns copy that contains banned steering language.
        stubMarketingGeneration(steeringPackageJson());

        ListingMarketingDraft draft = generate(listing.getId());

        // The draft is still saved DRAFTED (NOT blocked, NOT auto-published) — the human decides.
        assertThat(draft.getStatus()).isEqualTo(ListingMarketingDraft.Status.DRAFTED);

        // The Fair-Housing lint surfaced the banned term(s).
        assertThat(draft.isFairHousingFlagged()).isTrue();
        assertThat(draft.getFairHousingFlags()).isNotEmpty();
        assertThat(draft.getFairHousingFlags())
                .anySatisfy(f -> assertThat(f.getTerm()).isEqualTo("perfect for families"));
        assertThat(draft.getFairHousingFlags())
                .anySatisfy(f -> assertThat(f.getTerm()).isEqualTo("safe neighborhood"));
        // The flag carries the channel + a snippet so the agent can see the context.
        assertThat(draft.getFairHousingFlags().get(0).getChannel()).isNotNull();
        assertThat(draft.getFairHousingFlags().get(0).getSnippet()).isNotBlank();
    }

    // ── (3) approve → APPROVED + leaves the DRAFTED queue ────────────────────────

    @Test
    void approve_marksApproved_leavesDraftedQueue_emitsEvent() {
        Listing listing = createListing("789 Elm Rd");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(cleanPackageJson());
        ListingMarketingDraft draft = generate(listing.getId());

        ListingMarketingDraft approved = marketingService.approve(draft.getId())
                .contextWrite(TenantContextHolder.write(ctx())).block();

        assertThat(approved).isNotNull();
        assertThat(approved.getStatus()).isEqualTo(ListingMarketingDraft.Status.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();

        // It left the DRAFTED queue.
        List<ListingMarketingDraft> queue = marketingService.listDrafted()
                .contextWrite(TenantContextHolder.write(ctx())).collectList().block();
        assertThat(queue).extracting(ListingMarketingDraft::getId).doesNotContain(draft.getId());

        // The approved event fired.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(approvedEvents()).hasSize(1));
        assertThat(approvedEvents().get(0).payload().get("draftId")).isEqualTo(draft.getId().toString());

        // Re-approving a non-DRAFTED draft is rejected (the same-status guard).
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        marketingService.approve(draft.getId())
                                .contextWrite(TenantContextHolder.write(ctx())).block())
                .isInstanceOf(com.kumouri.kmodigipresbe.exceptions.DigiPresBeException.class);
    }

    // ── (4) a Claude (text) failure → best-effort partial/empty DRAFTED draft, no error ──

    @Test
    void textGenerationFailure_yieldsBestEffortDegradedDraft_noError() {
        Listing listing = createListing("1 Maple Ct");
        addPhoto(listing.getId());
        stubVisionCaption();
        // The text generation upstream 500s → best-effort degrade. The text call's content is a plain
        // string, so messages[0].content[0].type is ABSENT (the vision call's is "image"); priority 1
        // routes only the text call here. The vision stub (priority 2) still serves the image call.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.matchingJsonPath("$.messages[0].content[0].type",
                        WireMock.absent()))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        // generate must NOT throw — it returns a DRAFTED draft marked degraded.
        ListingMarketingDraft draft = generate(listing.getId());

        assertThat(draft.getStatus()).isEqualTo(ListingMarketingDraft.Status.DRAFTED);
        assertThat(draft.isGenerationDegraded()).isTrue();
        assertThat(draft.getPieces()).isEmpty();
        // The photo was still captioned (vision succeeded) — the partial draft keeps what it could.
        assertThat(draft.getPhotoCaptions()).hasSize(1);
    }

    // ── (5) never-auto-publish — generate alone never reaches APPROVED ────────────

    @Test
    void generate_neverAutoPublishes() {
        Listing listing = createListing("2 Birch Ln");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(cleanPackageJson());

        generate(listing.getId());

        // No draft is ever APPROVED by generation alone — every draft requires a human approve.
        List<ListingMarketingDraft> all = mongo.findAll(ListingMarketingDraft.class).collectList().block();
        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(d -> d.getStatus() == ListingMarketingDraft.Status.DRAFTED);
        // And no approved event fired from generation.
        assertThat(approvedEvents()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private List<DomainEvent> draftedEvents() {
        return observed.stream()
                .filter(e -> DomainEventType.LISTING_MARKETING_DRAFTED.equals(e.type())).toList();
    }

    private List<DomainEvent> approvedEvents() {
        return observed.stream()
                .filter(e -> DomainEventType.LISTING_MARKETING_APPROVED.equals(e.type())).toList();
    }

    private static String channelText(ListingMarketingDraft draft, MarketingChannel channel) {
        return draft.getPieces().stream()
                .filter(p -> p.getChannel() == channel)
                .map(ListingMarketingDraft.GeneratedPiece::getText)
                .findFirst().orElse(null);
    }

    private Listing createListing(String address) {
        return listingService.create(Listing.builder()
                        .addressLine(address).city("St. Louis").state("MO").zip("63101")
                        .price(new BigDecimal("450000")).beds(3).baths(new BigDecimal("2"))
                        .sqft(1800)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    private ListingPhoto addPhoto(UUID listingId) {
        return marketingService.addPhoto(listingId, FAKE_IMAGE, "image/jpeg", "front.jpg")
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    private ListingMarketingDraft generate(UUID listingId) {
        return marketingService.generate(listingId)
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    /** Stubs the per-photo vision {@code extract} call (the request carrying an image content block). */
    private void stubVisionCaption() {
        String inner = "{\\\"caption\\\":\\\"A bright modern kitchen.\\\","
                + "\\\"features\\\":[\\\"granite countertops\\\",\\\"stainless appliances\\\"]}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.matchingJsonPath("$.messages[0].content[0].type",
                        WireMock.equalTo("image")))
                .atPriority(2)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicBody(inner))));
    }

    /** Stubs the text marketing-generation call (the request whose content is a plain string). */
    private void stubMarketingGeneration(String innerEscaped) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.matchingJsonPath("$.messages[0].content[0].type",
                        WireMock.absent()))
                .atPriority(3)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicBody(innerEscaped))));
    }

    private static String anthropicBody(String innerEscaped) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + innerEscaped + "\"}],"
                + "\"usage\":{\"input_tokens\":200,\"output_tokens\":120}}";
    }

    /** A clean, Fair-Housing-safe per-channel package (escaped for embedding in the WireMock body). */
    private static String cleanPackageJson() {
        return "{\\\"mlsRemarks\\\":\\\"A charming 3-bedroom home with granite countertops.\\\","
                + "\\\"instagram\\\":\\\"New listing! #realestate #forsale\\\","
                + "\\\"facebook\\\":\\\"Just listed: a lovely home with a modern kitchen.\\\","
                + "\\\"x\\\":\\\"Just listed: 3BR with granite kitchen.\\\","
                + "\\\"emailBlast\\\":\\\"Hello, we are excited to share this new listing. "
                + "Contact us to schedule a tour.\\\"}";
    }

    /** A package containing banned steering language (the lint must flag it). */
    private static String steeringPackageJson() {
        return "{\\\"mlsRemarks\\\":\\\"This home is perfect for families in a safe neighborhood.\\\","
                + "\\\"instagram\\\":\\\"Great new home! #forsale\\\","
                + "\\\"facebook\\\":\\\"A wonderful family home.\\\","
                + "\\\"x\\\":\\\"Just listed: a lovely home.\\\","
                + "\\\"emailBlast\\\":\\\"Hello, this property is ideal for your family.\\\"}";
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        mongo.save(Tenant.builder()
                .id(tid).slug("re4-" + tid)
                .displayName("Gateway Realty RE-4 IT").status(Tenant.TenantStatus.ACTIVE)
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

    // ── in-memory FileStorageService that actually round-trips the bytes (RE-4 getBytes) ──

    /**
     * {@code @Primary} in-memory {@link FileStorageService}: {@code putBytes} stores the bytes keyed by the
     * returned ref and {@code getBytes} returns them — so the RE-4 generate path's read-back-then-caption
     * exercises real bytes through the (stubbed) vision call. The RE-4 read-twin of the mole/equipment
     * {@code putBytes}-only storage stubs.
     */
    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryStorageConfig {
        @Bean
        @Primary
        FileStorageService inMemoryFileStorageService() {
            return new FileStorageService() {
                private final Map<String, byte[]> store = new ConcurrentHashMap<>();

                @Override
                public Presigned presignUpload(UUID tenantId, String partition, String contentType,
                                               String suffix, Duration ttl) {
                    String ref = "tenants/" + tenantId + "/" + partition + "/"
                            + UUID.randomUUID() + "." + suffix;
                    return new Presigned("https://s3.test/presign-put/" + ref, ref, "PUT");
                }

                @Override
                public String presignDownload(UUID tenantId, String storageRef, Duration ttl) {
                    return "https://s3.test/presign-get/" + storageRef;
                }

                @Override
                public Mono<String> putBytes(UUID tenantId, String partition, byte[] bytes,
                                             String contentType, String suffix) {
                    String ref = "tenants/" + tenantId + "/" + partition + "/"
                            + UUID.randomUUID() + "." + suffix;
                    store.put(ref, bytes);
                    return Mono.just(ref);
                }

                @Override
                public Mono<byte[]> getBytes(UUID tenantId, String storageRef) {
                    byte[] bytes = store.get(storageRef);
                    return bytes == null ? Mono.just(new byte[0]) : Mono.just(bytes);
                }
            };
        }
    }
}
