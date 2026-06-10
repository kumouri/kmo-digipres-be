package com.kumouri.kmodigipresbe.module.realestate;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.model.files.Attachment;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.ListingPrepService;
import com.kumouri.kmodigipresbe.module.realestate.listingprep.model.ListingPrepPack;
import com.kumouri.kmodigipresbe.module.realestate.marketing.ListingMarketingService;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
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
import java.time.LocalDate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Estate Concierge T10 — the Listing Prep Studio: the reused RE-4 MLS description + email + per-photo
 * vision callouts, PLUS the genuine net-new — a <strong>4-week dated social calendar</strong> — packaged into
 * one {@link ListingPrepPack} with a draft → approve / skip lifecycle (NEVER auto-published).
 *
 * <h2>How it is made deterministic (no live Anthropic / Atlas / S3)</h2>
 * <ul>
 *   <li><strong>Anthropic</strong> → WireMock ({@code kmosf.ai.anthropic.base-url}). THREE call shapes POST
 *       to {@code /}, disambiguated by request body: the per-photo vision {@code extract} carries an
 *       {@code image} content block ({@code messages[0].content[0].type=="image"}); the reused RE-4
 *       description/email call carries the marketing user prompt ("Draft the marketing copy now."); the
 *       net-new calendar call carries the calendar user prompt ("4-week social calendar"). Three priority
 *       stubs route each to its canned JSON.</li>
 *   <li><strong>{@link FileStorageService}</strong> → an in-memory {@code @Primary} stub that actually stores
 *       + returns the photo bytes (so {@code getBytes} feeds the vision call) — the RE-4 read-twin.</li>
 *   <li><strong>{@link DomainEventPublisher}</strong> → the real publisher; a subscriber captures the
 *       {@code LISTING_PREP_GENERATED} / {@code LISTING_PREP_APPROVED} events.</li>
 * </ul>
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>generate → a DRAFTED pack with an MLS description + email + a 4-week DATED calendar (N posts spanning
 *       weeks 1-4, ascending dates) + per-photo (stubbed-vision) callouts; {@code LISTING_PREP_GENERATED} emitted;</li>
 *   <li><strong>the Fair-Housing proof on the calendar</strong> — a steering post is HELD + safe-substituted
 *       (never emitted), the pack stays DRAFTED;</li>
 *   <li>a steering description is flagged but the pack is still DRAFTED (the RE-4 review posture);</li>
 *   <li>approve → APPROVED + leaves the DRAFTED queue; {@code LISTING_PREP_APPROVED}; re-approve rejected (4461);</li>
 *   <li>a Claude (text) failure → a best-effort degraded DRAFTED pack (no error), photo callouts kept;</li>
 *   <li>never-auto-publish — generate alone leaves the pack DRAFTED.</li>
 * </ol>
 * The RE-1/2/3/4 ITs stay green as regression gates (run separately).
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RealEstateListingPrepIT.InMemoryStorageConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true",
        "kmosf.realestate.marketing-model=claude-sonnet-4-6",
        "kmosf.realestate.calendar-model=claude-sonnet-4-6",
        "kmosf.realestate.calendar-vision-model=claude-sonnet-4-5",
        "kmosf.realestate.calendar-posts-per-week=3"
})
class RealEstateListingPrepIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-t10-fake";
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
    @Autowired ListingPrepService prepService;
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
        mongo.remove(new Query(), ListingPrepPack.class).block();
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

    // ── (1) generate → DRAFTED pack: description + 4-week dated calendar + email + photo callouts ──

    @Test
    void generate_producesPrepPack_withDescription_4weekCalendar_emailAndPhotoCallouts_emitsEvent() {
        Listing listing = createListing("128 Lighthouse Way");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(cleanMarketingJson());
        stubCalendarGeneration(cleanCalendarJson());

        ListingPrepPack pack = generate(listing.getId(), LocalDate.of(2026, 6, 1));

        // DRAFTED, not degraded, not auto-published.
        assertThat(pack.getStatus()).isEqualTo(ListingPrepPack.Status.DRAFTED);
        assertThat(pack.getApprovedAt()).isNull();
        assertThat(pack.isGenerationDegraded()).isFalse();

        // Reused RE-4 description + email.
        assertThat(pack.getMlsDescription()).contains("charming");
        assertThat(pack.getEmailCampaign()).contains("Hello");

        // The net-new 4-week dated calendar: 4 posts, spanning weeks 1-4, ascending post dates.
        assertThat(pack.getSocialCalendar()).hasSize(4);
        assertThat(pack.getSocialCalendar()).extracting(ListingPrepPack.SocialPost::getWeekIndex)
                .containsExactlyInAnyOrder(1, 2, 3, 4);
        // Dates are assigned from the start date by dayOffset (never model-hallucinated) and sorted ascending.
        List<LocalDate> dates = pack.getSocialCalendar().stream()
                .map(ListingPrepPack.SocialPost::getPostDate).toList();
        for (int i = 1; i < dates.size(); i++) {
            assertThat(dates.get(i)).isAfterOrEqualTo(dates.get(i - 1));
        }
        assertThat(dates.get(0)).isEqualTo(LocalDate.of(2026, 6, 1)); // dayOffset 0
        assertThat(dates).contains(LocalDate.of(2026, 6, 22));        // week 4, dayOffset 21
        // Every clean post carries a channel + non-blank copy + is fair-housing-safe.
        assertThat(pack.getSocialCalendar()).allSatisfy(p -> {
            assertThat(p.getChannel()).isNotNull();
            assertThat(p.getCopy()).isNotBlank();
            assertThat(p.isFairHousingSafe()).isTrue();
        });
        assertThat(pack.getCalendarHeldCount()).isZero();

        // The listing photo was captioned by the (stubbed) vision call → a per-photo callout.
        assertThat(pack.getPhotoCaptions()).hasSize(1);
        ListingPrepPack.PhotoNote note = pack.getPhotoCaptions().get(0);
        assertThat(note.getCaption()).contains("kitchen");
        assertThat(note.getFeatures()).contains("granite countertops");

        // Clean copy → no Fair-Housing flags.
        assertThat(pack.isFairHousingFlagged()).isFalse();
        assertThat(pack.getFairHousingFlags()).isEmpty();

        // Queued for the agent (DRAFTED) and the event fired.
        List<ListingPrepPack> queue = prepService.listDrafted()
                .contextWrite(TenantContextHolder.write(ctx())).collectList().block();
        assertThat(queue).extracting(ListingPrepPack::getId).contains(pack.getId());

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(generatedEvents()).hasSize(1));
        assertThat(generatedEvents().get(0).payload().get("listingId")).isEqualTo(listing.getId().toString());
        assertThat(generatedEvents().get(0).payload().get("calendarPostCount")).isEqualTo(4);
    }

    // ── (2) THE FAIR-HOUSING PROOF ON THE CALENDAR — a steering post is held + safe-substituted ──

    @Test
    void steeringPostInCalendar_isHeldAndSafeSubstituted_neverEmitted() {
        Listing listing = createListing("456 Pine Ave");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(cleanMarketingJson());
        // The calendar stub returns one post containing banned steering language ("perfect for families").
        stubCalendarGeneration(steeringCalendarJson());

        ListingPrepPack pack = generate(listing.getId(), LocalDate.of(2026, 6, 1));

        // The pack is still DRAFTED (NOT blocked) — the calendar is corrected, never auto-published.
        assertThat(pack.getStatus()).isEqualTo(ListingPrepPack.Status.DRAFTED);

        // Exactly one post was held + safe-substituted; the rest are clean.
        assertThat(pack.getCalendarHeldCount()).isEqualTo(1);
        ListingPrepPack.SocialPost held = pack.getSocialCalendar().stream()
                .filter(p -> !p.isFairHousingSafe()).findFirst().orElseThrow();
        assertThat(held.getHeldReason()).isEqualTo("perfect for families");
        assertThat(held.getCopy()).isEqualTo(ListingPrepService.SAFE_POST_SUBSTITUTE);

        // THE INVARIANT: no emitted post copy contains the banned term — it was never let through.
        assertThat(pack.getSocialCalendar())
                .allSatisfy(p -> assertThat(p.getCopy().toLowerCase()).doesNotContain("perfect for families"));

        // The flag is recorded on the rollup with the CALENDAR surface + a snippet.
        assertThat(pack.isFairHousingFlagged()).isTrue();
        assertThat(pack.getFairHousingFlags())
                .anySatisfy(f -> {
                    assertThat(f.getTerm()).isEqualTo("perfect for families");
                    assertThat(f.getSurface()).startsWith("CALENDAR");
                    assertThat(f.getSnippet()).isNotBlank();
                });
    }

    // ── (3) a steering description is flagged but the pack is still DRAFTED (RE-4 review posture) ──

    @Test
    void steeringDescription_isFlagged_butPrepStillDrafted() {
        Listing listing = createListing("789 Elm Rd");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(steeringMarketingJson());
        stubCalendarGeneration(cleanCalendarJson());

        ListingPrepPack pack = generate(listing.getId(), LocalDate.of(2026, 6, 1));

        assertThat(pack.getStatus()).isEqualTo(ListingPrepPack.Status.DRAFTED);
        assertThat(pack.isFairHousingFlagged()).isTrue();
        assertThat(pack.getFairHousingFlags())
                .anySatisfy(f -> {
                    assertThat(f.getTerm()).isEqualTo("safe neighborhood");
                    assertThat(f.getSurface()).isEqualTo("DESCRIPTION");
                });
    }

    // ── (4) approve → APPROVED + leaves the DRAFTED queue; re-approve rejected ────

    @Test
    void approve_marksApproved_leavesDraftedQueue_emitsEvent() {
        Listing listing = createListing("1 Maple Ct");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(cleanMarketingJson());
        stubCalendarGeneration(cleanCalendarJson());
        ListingPrepPack pack = generate(listing.getId(), null);

        ListingPrepPack approved = prepService.approve(pack.getId())
                .contextWrite(TenantContextHolder.write(ctx())).block();

        assertThat(approved).isNotNull();
        assertThat(approved.getStatus()).isEqualTo(ListingPrepPack.Status.APPROVED);
        assertThat(approved.getApprovedAt()).isNotNull();

        List<ListingPrepPack> queue = prepService.listDrafted()
                .contextWrite(TenantContextHolder.write(ctx())).collectList().block();
        assertThat(queue).extracting(ListingPrepPack::getId).doesNotContain(pack.getId());

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(approvedEvents()).hasSize(1));
        assertThat(approvedEvents().get(0).payload().get("packId")).isEqualTo(pack.getId().toString());

        // Re-approving a non-DRAFTED pack is rejected (the same-status guard, 4461).
        assertThatThrownBy(() -> prepService.approve(pack.getId())
                .contextWrite(TenantContextHolder.write(ctx())).block())
                .isInstanceOfSatisfying(DigiPresBeException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(4461));
    }

    // ── (5) a Claude (text) failure → best-effort degraded DRAFTED pack, no error ─

    @Test
    void textGenerationFailure_yieldsDegradedPrepPack_noError() {
        Listing listing = createListing("2 Birch Ln");
        addPhoto(listing.getId());
        stubVisionCaption();
        // Both text calls (the RE-4 description/email + the calendar) 500; the vision call (stubbed above)
        // still serves the photo. Best-effort → a degraded DRAFTED pack.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.containing("marketing copywriter drafting listing marketing"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.containing("FOUR-WEEK social media CALENDAR"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        // generate must NOT throw — it returns a DRAFTED pack marked degraded.
        ListingPrepPack pack = generate(listing.getId(), LocalDate.of(2026, 6, 1));

        assertThat(pack.getStatus()).isEqualTo(ListingPrepPack.Status.DRAFTED);
        assertThat(pack.isGenerationDegraded()).isTrue();
        assertThat(pack.getMlsDescription()).isNull();
        assertThat(pack.getEmailCampaign()).isNull();
        assertThat(pack.getSocialCalendar()).isEmpty();
        // The photo was still captioned (vision succeeded) — the partial pack keeps what it could.
        assertThat(pack.getPhotoCaptions()).hasSize(1);
    }

    // ── (6) never-auto-publish — generate alone never reaches APPROVED ────────────

    @Test
    void generate_neverAutoPublishes() {
        Listing listing = createListing("3 Cedar St");
        addPhoto(listing.getId());
        stubVisionCaption();
        stubMarketingGeneration(cleanMarketingJson());
        stubCalendarGeneration(cleanCalendarJson());

        generate(listing.getId(), null);

        List<ListingPrepPack> all = mongo.findAll(ListingPrepPack.class).collectList().block();
        assertThat(all).isNotEmpty();
        assertThat(all).allMatch(p -> p.getStatus() == ListingPrepPack.Status.DRAFTED);
        assertThat(approvedEvents()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private List<DomainEvent> generatedEvents() {
        return observed.stream()
                .filter(e -> DomainEventType.LISTING_PREP_GENERATED.equals(e.type())).toList();
    }

    private List<DomainEvent> approvedEvents() {
        return observed.stream()
                .filter(e -> DomainEventType.LISTING_PREP_APPROVED.equals(e.type())).toList();
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

    private ListingPhoto addPhoto(UUID listingId) {
        return marketingService.addPhoto(listingId, FAKE_IMAGE, "image/jpeg", "front.jpg")
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    private ListingPrepPack generate(UUID listingId, LocalDate startDate) {
        return prepService.generate(listingId, startDate, null)
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    // The three Anthropic call shapes are disambiguated by a unique substring of their (mutually-exclusive)
    // SYSTEM prompts via a plain raw-body containing() scan (no JSONPath string-vs-array indexing quirks):
    // vision says "caption a real-estate listing photo"; the reused RE-4 description/email says "marketing
    // copywriter drafting listing marketing"; the net-new calendar says "FOUR-WEEK social media CALENDAR".

    /** Stubs the per-photo vision {@code extract} call (its system prompt captions a listing photo). */
    private void stubVisionCaption() {
        String inner = "{\\\"caption\\\":\\\"A bright modern kitchen.\\\","
                + "\\\"features\\\":[\\\"granite countertops\\\",\\\"stainless appliances\\\"]}";
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.containing("caption a real-estate listing photo"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicBody(inner))));
    }

    /** Stubs the reused RE-4 description/email call (its system prompt is the marketing copywriter). */
    private void stubMarketingGeneration(String innerEscaped) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.containing("marketing copywriter drafting listing marketing"))
                .atPriority(1)
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(anthropicBody(innerEscaped))));
    }

    /** Stubs the net-new 4-week calendar call (its system prompt is the FOUR-WEEK calendar planner). */
    private void stubCalendarGeneration(String innerEscaped) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(WireMock.containing("FOUR-WEEK social media CALENDAR"))
                .atPriority(1)
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

    /** A clean, Fair-Housing-safe description + email (RE-4 keys; escaped for the WireMock body). */
    private static String cleanMarketingJson() {
        return "{\\\"mlsRemarks\\\":\\\"A charming 4-bedroom coastal home with granite countertops.\\\","
                + "\\\"instagram\\\":\\\"New listing! #realestate\\\","
                + "\\\"facebook\\\":\\\"Just listed.\\\","
                + "\\\"x\\\":\\\"Just listed: 4BR coastal.\\\","
                + "\\\"emailBlast\\\":\\\"Hello, we are excited to share this new listing. Contact us to "
                + "schedule a tour.\\\"}";
    }

    /** A description containing banned steering language (the lint must flag it). */
    private static String steeringMarketingJson() {
        return "{\\\"mlsRemarks\\\":\\\"This home is in a safe neighborhood.\\\","
                + "\\\"instagram\\\":\\\"New home!\\\","
                + "\\\"facebook\\\":\\\"A wonderful home.\\\","
                + "\\\"x\\\":\\\"Just listed.\\\","
                + "\\\"emailBlast\\\":\\\"Hello, come see this home.\\\"}";
    }

    /** A clean 4-week calendar: 4 posts, one per week, dayOffsets 0/7/14/21 (escaped for the WireMock body). */
    private static String cleanCalendarJson() {
        return "{\\\"posts\\\":["
                + "{\\\"week\\\":1,\\\"dayOffset\\\":0,\\\"channel\\\":\\\"instagram\\\","
                + "\\\"copy\\\":\\\"Just listed! A stunning coastal retreat. #newlisting\\\"},"
                + "{\\\"week\\\":2,\\\"dayOffset\\\":7,\\\"channel\\\":\\\"facebook\\\","
                + "\\\"copy\\\":\\\"Feature spotlight: the granite kitchen.\\\"},"
                + "{\\\"week\\\":3,\\\"dayOffset\\\":14,\\\"channel\\\":\\\"x\\\","
                + "\\\"copy\\\":\\\"Open this weekend - come take a look.\\\"},"
                + "{\\\"week\\\":4,\\\"dayOffset\\\":21,\\\"channel\\\":\\\"instagram\\\","
                + "\\\"copy\\\":\\\"Still available — schedule your tour today. #realestate\\\"}"
                + "]}";
    }

    /** A 4-week calendar whose week-2 post contains banned steering language (the lint must hold it). */
    private static String steeringCalendarJson() {
        return "{\\\"posts\\\":["
                + "{\\\"week\\\":1,\\\"dayOffset\\\":0,\\\"channel\\\":\\\"instagram\\\","
                + "\\\"copy\\\":\\\"Just listed! A stunning coastal retreat. #newlisting\\\"},"
                + "{\\\"week\\\":2,\\\"dayOffset\\\":7,\\\"channel\\\":\\\"facebook\\\","
                + "\\\"copy\\\":\\\"This home is perfect for families!\\\"},"
                + "{\\\"week\\\":3,\\\"dayOffset\\\":14,\\\"channel\\\":\\\"x\\\","
                + "\\\"copy\\\":\\\"Open this weekend — come take a look.\\\"}"
                + "]}";
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        mongo.save(Tenant.builder()
                .id(tid).slug("t10-" + tid)
                .displayName("Harbor Point Realty T10 IT").status(Tenant.TenantStatus.ACTIVE)
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

    // ── in-memory FileStorageService that actually round-trips the bytes (the RE-4 read-twin) ──

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
