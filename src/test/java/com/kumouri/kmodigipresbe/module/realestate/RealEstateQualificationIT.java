package com.kumouri.kmodigipresbe.module.realestate;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.realestate.concierge.LeadHandoffService;
import com.kumouri.kmodigipresbe.module.realestate.concierge.QualificationService;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;
import com.kumouri.kmodigipresbe.module.realestate.model.DisclosureType;
import com.kumouri.kmodigipresbe.module.realestate.model.HotHandoffLog;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingDisclosureService;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
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
import java.time.Instant;
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
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Estate Concierge RE-2 — multi-turn qualification + the buyer {@code Deal} + the UNCHANGED nightly
 * {@code LeadScoringV2Service} tiering + the hot-handoff subscriber.
 *
 * <h2>How the two Anthropic calls are made deterministic (no live Anthropic)</h2>
 * Both the RE-1 grounded-answer call and the RE-2 qualification-extraction call POST to the same WireMock
 * URL ({@code /}); they are differentiated by request body — the answer call's user message contains
 * {@code "DISCLOSURE CONTEXT"}, the qualification call's contains {@code "Conversation so far"}. WireMock
 * body-matching ({@code withRequestBody(containing(...))}) returns a grounded answer for the former and a
 * strict-JSON {@code {budget, timeline, ...}} for the latter — so the test exercises the genuine
 * extract → accumulate → materialize-Deal path. {@link TwilioSmsService} is a {@code @MockitoBean} capture
 * seam; {@link VectorIndex} is the in-memory test index (the RE-1 IT pattern).
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>a buyer text that reveals budget/timeline → a concierge {@code Deal} is materialized (value=budget,
 *       customFields source=concierge + listingId + timeline), the conversation links contactId/dealId and
 *       advances to QUALIFYING — AND the grounded Q&A still answers with a citation (coexistence);</li>
 *   <li>the hot-handoff fires on a HOT {@code LEAD_SCORE_UPDATED} for a concierge realestate Deal (agent
 *       alert SMS sent, {@code HotHandoffLog} written) and is idempotent (a re-fired event = no 2nd send);</li>
 *   <li>the hot-handoff no-ops for a non-concierge Deal and for a non-HOT (WARM) tier (scoping correct);</li>
 *   <li>best-effort: a Claude qualification-extraction failure (WireMock 500) never drops the conversation
 *       or creates a Deal — the grounded answer still goes through.</li>
 * </ol>
 * The UNCHANGED scorer ({@code LeadScoringV2IT}) and the RE-1 grounding ({@code RealEstateConciergeIT}) +
 * ChairFill inbound ({@code GapFillWaitlistIT}) stay green as regression gates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, RealEstateQualificationIT.InMemoryVectorIndexConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true",
        "kmosf.realestate.concierge-model=claude-haiku-4-5",
        "kmosf.realestate.qualification-model=claude-haiku-4-5",
        "kmosf.realestate.retrieval-top-k=12",
        // flip the hot-handoff agent notify ON so the IT can assert the best-effort SMS is sent
        "kmosf.realestate.handoff-notify=true"
})
class RealEstateQualificationIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_re2";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-re2-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String TRACKED_NUMBER_A = "+16185559101";
    private static final String BUYER = "+16185550300";
    private static final String NOTIFY_PHONE = "+16185559999";

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
    @Autowired LeadHandoffService leadHandoffService;
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
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), HotHandoffLog.class).block();
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

    // ── (1) multi-turn qualification → Deal, with the grounded Q&A still working ──

    @Test
    void buyerRevealsBudget_materializesConciergeDeal_andStillAnswersGrounded() {
        Listing listing = createListing(TRACKED_NUMBER_A, "123 Oak St");
        ListingDisclosure roof = createDisclosure(listing.getId(), DisclosureType.ROOF,
                "Roof replaced 2019, architectural shingles, transferable warranty.");

        // The answer call (body contains DISCLOSURE CONTEXT) → a grounded answer.
        stubAnswer("The roof was replaced in 2019 (architectural shingles) with a transferable warranty.");
        // The qualification call (body contains Conversation so far) → strict JSON with budget+timeline.
        stubQualification("{\"budget\":450000,\"timeline\":\"60 days\",\"financing\":\"pre-approved\","
                + "\"preApproved\":true,\"intent\":\"BUY\"}");

        ConciergeConversation conv = inbound(TRACKED_NUMBER_A,
                "how old is the roof? my budget's around 450k and I'm pre-approved, hoping to buy in 60 days");

        // The grounded Q&A still works (coexistence): a non-handoff assistant turn cites the roof disclosure.
        ConciergeTurn assistant = lastAssistantTurn(conv);
        assertThat(assistant.isHandoff()).isFalse();
        assertThat(assistant.getBody()).contains("2019");
        assertThat(assistant.getCitations()).hasSize(1);
        assertThat(assistant.getCitations().get(0).getDisclosureId()).isEqualTo(roof.getId());

        // Qualification accumulated + a concierge Deal materialized + conversation advanced to QUALIFYING.
        assertThat(conv.getState()).isEqualTo(ConversationState.QUALIFYING);
        assertThat(conv.getQualification()).isNotNull();
        assertThat(conv.getQualification().getBudget()).isEqualByComparingTo(new BigDecimal("450000"));
        assertThat(conv.getQualification().getTimeline()).isEqualTo("60 days");
        assertThat(conv.getQualification().isDealMaterialized()).isTrue();
        assertThat(conv.getContactId()).isNotNull();
        assertThat(conv.getDealId()).isNotNull();

        // The Deal: stage NEW, value = budget, primaryContact = buyer, concierge + listing markers.
        Deal deal = mongo.findById(conv.getDealId(), Deal.class).block();
        assertThat(deal).isNotNull();
        assertThat(deal.getStage()).isEqualTo(PipelineStage.NEW);
        assertThat(deal.getValue()).isEqualByComparingTo(new BigDecimal("450000"));
        assertThat(deal.getPrimaryContactId()).isEqualTo(conv.getContactId());
        assertThat(deal.getCustomFields().get(QualificationService.DEAL_SOURCE_KEY))
                .isEqualTo(QualificationService.DEAL_SOURCE_CONCIERGE);
        assertThat(deal.getCustomFields().get(QualificationService.DEAL_LISTING_ID_KEY))
                .isEqualTo(listing.getId().toString());
        assertThat(deal.getCustomFields().get("timeline")).isEqualTo("60 days");
        assertThat(QualificationService.isConciergeSourced(deal)).isTrue();

        // The buyer Contact was found-or-created by phone.
        Contact buyer = mongo.findById(conv.getContactId(), Contact.class).block();
        assertThat(buyer).isNotNull();
        assertThat(buyer.getPhones()).anyMatch(p -> BUYER.equals(p.number()));

        // The grounded answer was texted to the buyer (the qualification is silent — no extra SMS yet).
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("2019");
        assertThat(sentSms.get(0).to().e164()).isEqualTo(BUYER);
    }

    // ── (2) hot-handoff fires on a HOT concierge realestate Deal + is idempotent ──

    @Test
    void hotHandoff_firesAgentAlert_onHotConciergeDeal_andIsIdempotent() {
        Listing listing = createListing(TRACKED_NUMBER_A, "123 Oak St");
        UUID contactId = seedBuyerContact(LeadScore.TIER_HOT, 0.8);
        Deal deal = seedConciergeDeal(contactId, listing.getId(), new BigDecimal("450000"));

        // Drive the subscriber deterministically (the visible-for-test entry) on a HOT score update.
        leadHandoffService.handle(scoreEvent(contactId, 0.8, LeadScore.TIER_HOT)).block();

        // The agent was alerted (best-effort SMS to the per-tenant notifyPhone) ...
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(NOTIFY_PHONE);
        assertThat(sentSms.get(0).body().toLowerCase()).contains("hot");

        // ... and the idempotency ledger row was written for (tenant, deal).
        List<HotHandoffLog> log = mongo.findAll(HotHandoffLog.class).collectList().block();
        assertThat(log).hasSize(1);
        assertThat(log.get(0).getDealId()).isEqualTo(deal.getId());
        assertThat(log.get(0).getContactId()).isEqualTo(contactId);
        assertThat(log.get(0).getListingId()).isEqualTo(listing.getId());

        // Re-firing the same HOT score does ZERO duplicate work (ledger-insert-FIRST guard).
        leadHandoffService.handle(scoreEvent(contactId, 0.8, LeadScore.TIER_HOT)).block();
        assertThat(sentSms).as("no duplicate agent alert").hasSize(1);
        assertThat(mongo.findAll(HotHandoffLog.class).collectList().block()).hasSize(1);
    }

    // ── (3) no-op for a non-concierge Deal and for a non-HOT (WARM) tier ─────────

    @Test
    void hotHandoff_noOps_forNonConciergeDeal() {
        UUID contactId = seedBuyerContact(LeadScore.TIER_HOT, 0.9);
        // A plain (non-concierge) Deal — no concierge/listing markers.
        seedPlainDeal(contactId, new BigDecimal("300000"));

        leadHandoffService.handle(scoreEvent(contactId, 0.9, LeadScore.TIER_HOT)).block();

        assertThat(sentSms).as("no handoff for a non-concierge deal").isEmpty();
        assertThat(mongo.findAll(HotHandoffLog.class).collectList().block()).isEmpty();
    }

    @Test
    void hotHandoff_noOps_forNonHotTier() {
        Listing listing = createListing(TRACKED_NUMBER_A, "123 Oak St");
        UUID contactId = seedBuyerContact(LeadScore.TIER_WARM, 0.5);
        seedConciergeDeal(contactId, listing.getId(), new BigDecimal("450000"));

        leadHandoffService.handle(scoreEvent(contactId, 0.5, LeadScore.TIER_WARM)).block();

        assertThat(sentSms).as("no handoff for a WARM tier").isEmpty();
        assertThat(mongo.findAll(HotHandoffLog.class).collectList().block()).isEmpty();
    }

    // ── (4) best-effort: a Claude qualification failure never drops the conversation ──

    @Test
    void qualificationExtractionFailure_isBestEffort_answerStillGoesThrough_noDeal() {
        Listing listing = createListing(TRACKED_NUMBER_A, "123 Oak St");
        createDisclosure(listing.getId(), DisclosureType.ROOF,
                "Roof replaced 2019, architectural shingles, transferable warranty.");

        stubAnswer("The roof was replaced in 2019 with a transferable warranty.");
        // The qualification call fails upstream (non-200) → the extractor degrades to empty (4260).
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(containing("Conversation so far"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        ConciergeConversation conv = inbound(TRACKED_NUMBER_A,
                "how old is the roof? budget is 450k");

        // The grounded answer still happened + was texted (the answer path is never affected).
        ConciergeTurn assistant = lastAssistantTurn(conv);
        assertThat(assistant.isHandoff()).isFalse();
        assertThat(assistant.getBody()).contains("2019");
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("2019");

        // No Deal materialized (the extraction degraded to empty → no budget signal → no Deal); the
        // conversation survives (state ASKING, no link).
        assertThat(mongo.findAll(Deal.class).collectList().block()).isEmpty();
        assertThat(conv.getState()).isEqualTo(ConversationState.ASKING);
        assertThat(conv.getDealId()).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Drives a signed realestate-mode inbound Twilio SMS over the reused webhook; returns the conversation. */
    private ConciergeConversation inbound(String trackedNumber, String body) {
        postSms(BUYER, trackedNumber, body).expectStatus().isOk();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<ConciergeConversation> all = mongo.findAll(ConciergeConversation.class).collectList().block();
            assertThat(all).hasSize(1);
            assertThat(all.get(0).getTurns().stream()
                    .anyMatch(t -> t.getRole() == ConciergeTurn.Role.ASSISTANT)).isTrue();
        });
        // Settle the post-answer qualification step (it saves the conversation again).
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() ->
                assertThat(mongo.findAll(ConciergeConversation.class).collectList().block()
                        .get(0).getUpdatedAt()).isNotNull());
        return mongo.findAll(ConciergeConversation.class).collectList().block().get(0);
    }

    private static ConciergeTurn lastAssistantTurn(ConciergeConversation conv) {
        return conv.getTurns().stream()
                .filter(t -> t.getRole() == ConciergeTurn.Role.ASSISTANT)
                .reduce((a, b) -> b)
                .orElseThrow();
    }

    private DomainEvent scoreEvent(UUID contactId, double score, String tier) {
        return DomainEvent.of(DomainEventType.LEAD_SCORE_UPDATED, tenantId, contactId,
                Map.of("contactId", contactId.toString(), "score", score, "tier", tier,
                        "source", LeadScore.SOURCE_RULES_FALLBACK));
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

    private UUID seedBuyerContact(String tier, double score) {
        UUID id = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(id).tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName("Listing buyer " + BUYER)
                .phones(List.of(PhoneNumber.builder().number(BUYER).label("concierge").build()))
                .leadScore(new LeadScore(score, tier, LeadScore.SOURCE_RULES_FALLBACK, Instant.now()))
                .build()).block();
        return id;
    }

    private Deal seedConciergeDeal(UUID contactId, UUID listingId, BigDecimal budget) {
        Map<String, Object> cf = new HashMap<>();
        cf.put(QualificationService.DEAL_SOURCE_KEY, QualificationService.DEAL_SOURCE_CONCIERGE);
        cf.put(QualificationService.DEAL_LISTING_ID_KEY, listingId.toString());
        Deal deal = Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Buyer inquiry — concierge")
                .stage(PipelineStage.NEW)
                .value(budget)
                .primaryContactId(contactId)
                .customFields(cf)
                .build();
        return mongo.save(deal).block();
    }

    private Deal seedPlainDeal(UUID contactId, BigDecimal value) {
        Deal deal = Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Plain deal")
                .stage(PipelineStage.NEW)
                .value(value)
                .primaryContactId(contactId)
                .build();
        return mongo.save(deal).block();
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

    /** The grounded-answer Anthropic stub — matches the call whose body carries the DISCLOSURE CONTEXT. */
    private void stubAnswer(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(containing("DISCLOSURE CONTEXT"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageBody(text))));
    }

    /** The qualification-extraction Anthropic stub — matches the call whose body carries the transcript. */
    private void stubQualification(String json) {
        // The JSON is the model's text answer; escape quotes for embedding in the message body.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .withRequestBody(containing("Conversation so far"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(messageBody(json.replace("\"", "\\\"")))));
    }

    private static String messageBody(String text) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}";
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        mongo.save(Tenant.builder()
                .id(tid).slug("re2-" + tid)
                .displayName("Gateway Realty RE-2 IT").status(Tenant.TenantStatus.ACTIVE)
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

    /** Twilio connection in realestate SMS mode + a per-tenant notifyPhone for the hot-handoff. */
    private void seedTwilio(UUID tid) {
        Map<String, String> config = new HashMap<>();
        config.put("smsMode", "realestate");
        config.put("notifyPhone", NOTIFY_PHONE);
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_re2",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", TRACKED_NUMBER_A)))
                .config(config)
                .build()).block();
    }

    // ── deterministic in-memory VectorIndex (the RE-1 IT pattern) ────────────────

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

        @Override
        public Mono<Void> upsert(UUID tenantId, String sourceType, UUID sourceId,
                                 float[] vector, Map<String, Object> metadata) {
            store.put(new Key(tenantId, sourceType, sourceId), new Stored(sourceType, sourceId, metadata));
            return Mono.empty();
        }

        @Override
        public Flux<VectorSearchHit> search(UUID tenantId, float[] queryVector, int topK,
                                            @Nullable String keywordFilter) {
            return Flux.fromIterable(store.entrySet().stream()
                    .filter(e -> e.getKey().tenantId().equals(tenantId))
                    .map(e -> new VectorSearchHit(e.getValue().sourceType(), e.getValue().sourceId(),
                            0.9, e.getValue().metadata()))
                    .limit(topK)
                    .toList());
        }
    }
}
