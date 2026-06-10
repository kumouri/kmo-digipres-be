package com.kumouri.kmodigipresbe.module.realestate;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;
import com.kumouri.kmodigipresbe.module.realestate.model.DisclosureType;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.module.realestate.model.OfferedShowingSlot;
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
 * Real Estate Concierge RE-3 — book a showing over SMS: the {@code OFFERING_SLOTS} → {@code BOOKED} states.
 *
 * <h2>How the flow is made deterministic (no live Cal.com / Twilio; the slot copy is templated)</h2>
 * The booking flow itself makes <strong>no Anthropic call</strong> — slot generation + the offer/confirmation
 * copy are deterministic templating. So the offer/pick turns must NOT hit WireMock (asserted). The grounded
 * Q&A coexistence test re-uses the RE-1 WireMock Anthropic stub. {@link VectorIndex} is the in-memory test
 * index (the RE-1/RE-2 IT pattern); {@link TwilioSmsService} is a {@code @MockitoBean} capture seam; a
 * {@code @Primary} {@code DomainEventPublisher} captures the {@code SHOWING_BOOKED} event. The
 * {@code Meeting} is written DIRECTLY (the demo path — no Cal.com), so we assert against the persisted
 * {@code Meeting} + the conversation's persisted offered slot.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>a buyer who expresses showing intent ("can I see it Saturday?") → gets offered candidate slots over
 *       SMS ("reply 1 or 2"), the conversation advances to {@code OFFERING_SLOTS} with the slots persisted,
 *       and NO model call was made for that turn;</li>
 *   <li>the buyer's slot-pick reply ("2") → a {@code Meeting} is written (tenant + the listing address as
 *       location + the buyer attendee + the chosen start/end matching the offered slot), the conversation
 *       advances to {@code BOOKED} + links {@code meetingId}/{@code contactId}, a confirmation SMS is sent,
 *       {@code SHOWING_BOOKED} is emitted, and an {@code Activity(MEETING)} is logged on the buyer;</li>
 *   <li>a grounded factual question still answers with a citation (the RE-1 path is intact — coexistence);</li>
 *   <li>best-effort: a no-match pick re-offers (the conversation is never dropped, no Meeting written), and a
 *       Meeting-write failure on a valid pick is swallowed (the conversation survives, no confirmation).</li>
 * </ol>
 * The RE-1 grounding ({@code RealEstateConciergeIT}), RE-2 qualification ({@code RealEstateQualificationIT}),
 * the scorer ({@code LeadScoringV2IT}), and the ChairFill inbound ({@code GapFillWaitlistIT}) stay green as
 * regression gates.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class,
        RealEstateShowingBookingIT.InMemoryVectorIndexConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true",
        "kmosf.realestate.concierge-model=claude-haiku-4-5",
        "kmosf.realestate.qualification-model=claude-haiku-4-5",
        "kmosf.realestate.retrieval-top-k=12",
        // a deterministic 2-slot offer (Sat-ish 2pm/4pm-style hours; the exact day floats off "now")
        "kmosf.realestate.showing-slot-count=2",
        "kmosf.realestate.showing-slot-duration-minutes=30",
        "kmosf.realestate.showing-slot-hours=14,16"
})
class RealEstateShowingBookingIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_re3";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-re3-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String TRACKED_NUMBER_A = "+16185559201";
    private static final String BUYER = "+16185550400";

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
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private List<DomainEvent> observed;
    private reactor.core.Disposable eventSub;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Listing.class).block();
        mongo.remove(new Query(), ListingDisclosure.class).block();
        mongo.remove(new Query(), ConciergeConversation.class).block();
        mongo.remove(new Query(), Meeting.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        vectorIndex.clear();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        observed = new CopyOnWriteArrayList<>();
        eventSub = eventPublisher.stream().subscribe(observed::add);

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("realestate"));
        seedAnthropic(tenantId);
        seedTwilio(tenantId);
    }

    @org.junit.jupiter.api.AfterEach
    void disposeSub() {
        if (eventSub != null) {
            eventSub.dispose();
        }
    }

    private List<DomainEvent> showingBookedEvents() {
        return observed.stream().filter(e -> DomainEventType.SHOWING_BOOKED.equals(e.type())).toList();
    }

    private TenantContext ctx() {
        return new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    // ── (1) showing intent → slots offered over SMS, no model call ───────────────

    @Test
    void showingIntent_offersSlotsOverSms_advancesToOfferingSlots_noModelCall() {
        createListing(TRACKED_NUMBER_A, "123 Oak St");

        ConciergeConversation conv = inbound(TRACKED_NUMBER_A, "can I see it Saturday?");

        // The conversation advanced to OFFERING_SLOTS with the candidate slots persisted (count=2).
        assertThat(conv.getState()).isEqualTo(ConversationState.OFFERING_SLOTS);
        assertThat(conv.getOfferedSlots()).hasSize(2);
        assertThat(conv.getOfferedSlots().get(0).getOrdinal()).isEqualTo(1);
        assertThat(conv.getOfferedSlots().get(1).getOrdinal()).isEqualTo(2);
        assertThat(conv.getMeetingId()).isNull();

        // The offer SMS was texted to the buyer ("reply 1 or 2"); no fabricated grounded answer.
        assertThat(sentSms).hasSize(1);
        String offer = sentSms.get(0).body();
        assertThat(offer.toLowerCase()).contains("reply");
        assertThat(offer).contains(conv.getOfferedSlots().get(0).getLabel());
        assertThat(offer).contains(conv.getOfferedSlots().get(1).getLabel());
        assertThat(sentSms.get(0).to().e164()).isEqualTo(BUYER);

        // The booking offer is deterministic templating — the model was NEVER called for this turn.
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));

        // No Meeting yet; no SHOWING_BOOKED yet.
        assertThat(mongo.findAll(Meeting.class).collectList().block()).isEmpty();
        assertThat(showingBookedEvents()).isEmpty();
    }

    // ── (2) slot pick → Meeting written, BOOKED, confirmation SMS, event + activity ──

    @Test
    void slotPick_writesMeeting_advancesToBooked_confirms_emitsEvent_logsActivity() {
        Listing listing = createListing(TRACKED_NUMBER_A, "123 Oak St");

        // Turn 1: showing intent → slots offered.
        ConciergeConversation offered = inbound(TRACKED_NUMBER_A, "can I see it Saturday?");
        assertThat(offered.getState()).isEqualTo(ConversationState.OFFERING_SLOTS);
        OfferedShowingSlot picked = offered.getOfferedSlots().get(1); // we'll reply "2"
        sentSms.clear();

        // Turn 2: the buyer picks slot 2.
        ConciergeConversation booked = inboundUntilState(TRACKED_NUMBER_A, "2", ConversationState.BOOKED);

        // The conversation is BOOKED, links the Meeting + the buyer contact, and cleared the offered slots.
        assertThat(booked.getState()).isEqualTo(ConversationState.BOOKED);
        assertThat(booked.getMeetingId()).isNotNull();
        assertThat(booked.getContactId()).isNotNull();
        assertThat(booked.getOfferedSlots()).isEmpty();

        // A Meeting projection was written for the chosen slot (tenant, listing address, buyer attendee, time).
        Meeting meeting = mongo.findById(booked.getMeetingId(), Meeting.class).block();
        assertThat(meeting).isNotNull();
        assertThat(meeting.getTenantId()).isEqualTo(tenantId);
        assertThat(meeting.getName()).contains("123 Oak St");
        assertThat(meeting.getLocation()).isEqualTo("123 Oak St");
        assertThat(meeting.getStart()).isEqualTo(picked.getStart());
        assertThat(meeting.getEnd()).isEqualTo(picked.getEnd());
        assertThat(meeting.getAttendeeContactIds()).containsExactly(booked.getContactId());
        // Demo path: written directly, no Cal.com booking uid.
        assertThat(meeting.getCalComBookingUid()).isNull();

        // A confirmation SMS was sent ("Booked! ...").
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body().toLowerCase()).contains("booked");
        assertThat(sentSms.get(0).body()).contains(picked.getLabel());
        assertThat(sentSms.get(0).to().e164()).isEqualTo(BUYER);

        // The picking turn made no model call either.
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));

        // SHOWING_BOOKED was emitted with the meeting/listing/contact correlation.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(showingBookedEvents()).hasSize(1));
        DomainEvent event = showingBookedEvents().get(0);
        assertThat(event.payload().get("meetingId")).isEqualTo(meeting.getId().toString());
        assertThat(event.payload().get("listingId")).isEqualTo(listing.getId().toString());

        // An Activity(MEETING) was logged on the buyer contact (the CalComWebhookService pattern).
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Activity> acts = mongo.findAll(Activity.class).collectList().block();
            assertThat(acts).anyMatch(a -> a.getType() == ActivityType.MEETING
                    && a.getSubjectType() == SubjectType.CONTACT
                    && booked.getContactId().equals(a.getSubjectId()));
        });
    }

    // ── (3) a grounded factual question still answers (RE-1 path intact) ─────────

    @Test
    void groundedQuestion_stillAnswersWithCitation_whenNotBooking() {
        Listing listing = createListing(TRACKED_NUMBER_A, "123 Oak St");
        ListingDisclosure roof = createDisclosure(listing.getId(), DisclosureType.ROOF,
                "Roof replaced 2019, architectural shingles, transferable warranty.");
        stubGroundedAnswer("The roof was replaced in 2019 (architectural shingles) with a transferable "
                + "warranty.");

        ConciergeConversation conv = inbound(TRACKED_NUMBER_A, "how old is the roof?");

        // The RE-1 grounded path is intact: a cited, non-handoff answer; the conversation is NOT in a booking
        // state (a pure factual question never enters the booking flow).
        ConciergeTurn assistant = lastAssistantTurn(conv);
        assertThat(assistant.isHandoff()).isFalse();
        assertThat(assistant.getBody()).contains("2019");
        assertThat(assistant.getCitations()).hasSize(1);
        assertThat(assistant.getCitations().get(0).getDisclosureId()).isEqualTo(roof.getId());
        assertThat(conv.getState()).isEqualTo(ConversationState.ASKING);
        assertThat(conv.getOfferedSlots()).isEmpty();

        // The grounded answer was texted; the model WAS called exactly once (RE-1 behavior unchanged).
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("2019");
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));

        // No booking happened.
        assertThat(mongo.findAll(Meeting.class).collectList().block()).isEmpty();
    }

    // ── (4) best-effort: a no-match pick re-offers; the conversation is never dropped ──

    @Test
    void unrecognizedPick_reOffers_withoutBookingOrDroppingConversation() {
        createListing(TRACKED_NUMBER_A, "123 Oak St");

        // Turn 1: offer slots.
        ConciergeConversation offered = inbound(TRACKED_NUMBER_A, "can I tour it?");
        assertThat(offered.getState()).isEqualTo(ConversationState.OFFERING_SLOTS);
        sentSms.clear();

        // Turn 2: a reply that matches no offered slot (count=2, so "9" is out of range).
        ConciergeConversation still = inbound(TRACKED_NUMBER_A, "9");

        // Still OFFERING_SLOTS (re-offered), no Meeting, the slots are retained, the conversation survives.
        assertThat(still.getState()).isEqualTo(ConversationState.OFFERING_SLOTS);
        assertThat(still.getMeetingId()).isNull();
        assertThat(still.getOfferedSlots()).hasSize(2);
        assertThat(mongo.findAll(Meeting.class).collectList().block()).isEmpty();

        // A (re-offer) SMS was sent; no model call.
        assertThat(sentSms).hasSize(1);
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
        assertThat(showingBookedEvents()).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /** Drives a signed realestate-mode inbound SMS, waits for the assistant/save settle, returns the conv. */
    private ConciergeConversation inbound(String trackedNumber, String body) {
        long before = sentSms.size();
        postSms(BUYER, trackedNumber, body).expectStatus().isOk();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            List<ConciergeConversation> all = mongo.findAll(ConciergeConversation.class).collectList().block();
            assertThat(all).hasSize(1);
            // a reply was sent for this inbound (the offer / answer / confirm / re-offer)
            assertThat(sentSms.size()).isGreaterThan((int) before);
        });
        return mongo.findAll(ConciergeConversation.class).collectList().block().get(0);
    }

    /** Drives an inbound and waits until the conversation reaches {@code target} (the booking settle). */
    private ConciergeConversation inboundUntilState(String trackedNumber, String body,
                                                    ConversationState target) {
        postSms(BUYER, trackedNumber, body).expectStatus().isOk();
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            ConciergeConversation c = mongo.findAll(ConciergeConversation.class).collectList().block().get(0);
            assertThat(c.getState()).isEqualTo(target);
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

    /** A canned Anthropic answer (used as the grounded reply for the coexistence test). */
    private void stubGroundedAnswer(String text) {
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
                .id(tid).slug("re3-" + tid)
                .displayName("Gateway Realty RE-3 IT").status(Tenant.TenantStatus.ACTIVE)
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
                        "accountSid", "AC_test_re3",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", TRACKED_NUMBER_A)))
                .config(new HashMap<>(Map.of("smsMode", "realestate")))
                .build()).block();
    }

    // ── deterministic in-memory VectorIndex (the RE-1/RE-2 IT pattern) ────────────

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
        public Mono<Void> delete(UUID tenantId, String sourceType, UUID sourceId) {
            store.remove(new Key(tenantId, sourceType, sourceId));
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
