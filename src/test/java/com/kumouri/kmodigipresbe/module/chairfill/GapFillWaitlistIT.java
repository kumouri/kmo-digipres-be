package com.kumouri.kmodigipresbe.module.chairfill;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.gapfill.GapFillService;
import com.kumouri.kmodigipresbe.module.chairfill.gapfill.WaitlistClaimService;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
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
import reactor.core.publisher.Mono;

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
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * ChairFill CF-3 — GapFillWaitlistIT: the gap-fill waitlist auto-offer showpiece, double-YES-correct.
 *
 * <p>Drives the public {@code salon-waitlist} join widget + the net-new inbound-SMS webhook over HTTP
 * ({@code WebTestClient}, the {@code TwilioVoicemailIT} signed-webhook pattern), and the
 * {@link GapFillService} subscriber via its visible-for-test {@code handle(event)} (the CF-2
 * {@code RiskTieredPreventionIT} posture — deterministic, no live event-bus race). Anthropic →
 * WireMock ({@code kmosf.ai.anthropic.base-url}); {@link TwilioSmsService} → a {@code @MockitoBean}
 * capture seam (its base URL is not config-driven — the {@code RiskTieredPreventionIT}/
 * {@code TwilioVoicemailIT} precedent). No live external (§7).
 *
 * <h2>Coverage (plan CF-3 ITs)</h2>
 * <ol>
 *   <li>waitlist-join widget (token-gated) creates a {@link WaitlistEntry}; a wrong widgetType → 4230;</li>
 *   <li>{@code SalonBookingService.cancel()} emits {@code BOOKING_CANCELLED};</li>
 *   <li>a cancel → ranked offers sent (the inverted-risk ordering: a reliable regular ranks ABOVE a
 *       historically-flaky client) with the Claude personalized copy (and a generic fallback on a
 *       Claude failure);</li>
 *   <li>an inbound "YES" claims the slot — a real Booking is created via the normal path;</li>
 *   <li><strong>concurrent double-YES → exactly one booking + one apology</strong> (the crown jewel);</li>
 *   <li>an expired offer can't be claimed (NO_OPEN_OFFER, no booking);</li>
 *   <li>an inbound "STOP" sets the {@code sms-opt-out} consent tag;</li>
 *   <li>a non-chairfill tenant is a hard no-op (no offer);</li>
 *   <li>a bad inbound-SMS signature → 401/4000, zero effect.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        "kmosf.chairfill.offer-draft-model=claude-haiku-4-5",
        "kmosf.chairfill.gapfill.max-offers=3",
        "kmosf.chairfill.gapfill.offer-ttl-minutes=10",
        // Deterministic widget-token secret so the test can mint matching tokens.
        "kmosf.security.widget-token-secret=cf3-test-widget-secret-0123456789"
})
class GapFillWaitlistIT {

    private static final String AUTH_TOKEN = "twilio_test_authtoken_cf3";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-cf3-fake";
    private static final String FORWARDED_HOST = "api-demo.kmosolutionsfoundry.test";
    private static final String MENU_ITEM_ID = "balayage";
    private static final String BUSINESS_NUMBER = "+16185550100";

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
    @Autowired GapFillService gapFillService;
    @Autowired WaitlistClaimService claimService;
    @Autowired PublicWidgetTokenService tokenService;
    @Autowired TenantRepository tenants;
    @Autowired ContactRepository contacts;
    @Autowired SalonBookingService bookingService;
    @Autowired DomainEventPublisher eventPublisher;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private UUID stylistId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Booking.class).block();
        mongo.remove(new Query(), ServiceMenu.class).block();
        mongo.remove(new Query(), StaffMember.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), WaitlistEntry.class).block();
        mongo.remove(new Query(), WaitlistOffer.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        // The per-slot claim collection (not a repo entity — WorkOrderNumberGenerator pattern).
        mongo.getCollection("chairfill_waitlist_claims")
                .flatMap(c -> Mono.from(c.deleteMany(new org.bson.Document()))).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedAnthropic(tenantId);
        seedTwilio(tenantId);
        seedMenu(tenantId, new BigDecimal("200.00"));
        stylistId = seedStylist(tenantId, "Mia");
    }

    // ── 1. Waitlist-join widget ──────────────────────────────────────────────

    @Test
    void waitlistJoinWidget_createsEntry() {
        String token = tokenService.issue(tenantId, "salon-waitlist", Duration.ofHours(1));

        web.post().uri("/public/widget/salon-waitlist/" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", "dana@example.test",
                        "firstName", "Dana",
                        "phone", "+16185550150",
                        "serviceMenuItemId", MENU_ITEM_ID))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.waitlistEntryId").isNotEmpty()
                .jsonPath("$.status").isEqualTo("OPEN");

        List<WaitlistEntry> all = mongo.findAll(WaitlistEntry.class).collectList().block();
        assertThat(all).hasSize(1);
        assertThat(all.get(0).getTenantId()).isEqualTo(tenantId);
        assertThat(all.get(0).isSmsOptIn()).isTrue();
        assertThat(all.get(0).getServiceMenuItemId()).isEqualTo(MENU_ITEM_ID);
    }

    @Test
    void waitlistJoinWidget_wrongWidgetType_4230() {
        // A correctly-signed token for the WRONG widget type → 4230.
        String token = tokenService.issue(tenantId, "salon-booking", Duration.ofHours(1));

        web.post().uri("/public/widget/salon-waitlist/" + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "x@example.test", "phone", "+16185550150"))
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4230);

        assertThat(mongo.findAll(WaitlistEntry.class).collectList().block()).isEmpty();
    }

    // ── 2. cancel() emits BOOKING_CANCELLED ──────────────────────────────────

    @Test
    void cancel_emitsBookingCancelled() {
        UUID contactId = seedContact("Cara", "+16185550160", Set.of());
        UUID bookingId = seedBooking(tenantId, contactId, stylistId, BookingStatus.CONFIRMED,
                Instant.now().plus(Duration.ofHours(3)));

        List<DomainEvent> observed = new CopyOnWriteArrayList<>();
        var sub = eventPublisher.stream()
                .filter(e -> DomainEventType.BOOKING_CANCELLED.equals(e.type()))
                .subscribe(observed::add);
        try {
            bookingService.cancel(bookingId)
                    .contextWrite(TenantContextHolder.write(
                            new TenantContext(tenantId, null, Set.of("SYSTEM"))))
                    .block();

            // The booking is CANCELLED (existing behavior) AND the event fired (the additive emit).
            Booking after = mongo.findById(bookingId, Booking.class).block();
            assertThat(after.getStatus()).isEqualTo(BookingStatus.CANCELLED);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(observed).hasSize(1);
                Map<String, Object> p = observed.get(0).payload();
                assertThat(p.get("bookingId")).isEqualTo(bookingId);
                assertThat(p.get("staffMemberId")).isEqualTo(stylistId);
                assertThat(p.get("serviceMenuItemId")).isEqualTo(MENU_ITEM_ID);
                assertThat(p).containsKeys("scheduledStart", "scheduledEnd");
            });
        } finally {
            sub.dispose();
        }
    }

    // ── 3. Ranked offers — inverted risk ordering + Claude copy ──────────────

    @Test
    void gapFill_ranksReliableAboveFlaky_andSendsPersonalizedOffer() {
        stubOffer("Hi! A 2:30 just opened with Mia today — reply YES in the next 10 minutes and it's yours.");

        // Reliable regular: several COMPLETED, recent, no NO_SHOW → low no-show risk → ranks FIRST.
        UUID reliable = seedContact("Dana", "+16185550150", Set.of());
        seedTerminal(tenantId, reliable, BookingStatus.COMPLETED, Instant.now().minus(Duration.ofDays(10)));
        seedTerminal(tenantId, reliable, BookingStatus.COMPLETED, Instant.now().minus(Duration.ofDays(30)));
        UUID reliableEntry = seedEntry(tenantId, reliable, MENU_ITEM_ID);

        // Flaky: a prior NO_SHOW → high no-show risk → ranks LAST.
        UUID flaky = seedContact("Flo", "+16185550151", Set.of());
        seedTerminal(tenantId, flaky, BookingStatus.NO_SHOW, Instant.now().minus(Duration.ofDays(12)));
        seedTerminal(tenantId, flaky, BookingStatus.COMPLETED, Instant.now().minus(Duration.ofDays(40)));
        UUID flakyEntry = seedEntry(tenantId, flaky, MENU_ITEM_ID);

        UUID freedBookingId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        Integer sent = gapFillService.handle(
                cancelledEvent(tenantId, freedBookingId, stylistId, MENU_ITEM_ID, slotStart)).block();

        assertThat(sent).isEqualTo(2);

        // Two offers minted; rank 0 = the reliable regular (inverted no-show model), rank 1 = the flaky.
        List<WaitlistOffer> all = mongo.findAll(WaitlistOffer.class).collectList().block();
        assertThat(all).hasSize(2);
        WaitlistOffer rank0 = all.stream().filter(o -> o.getRank() == 0).findFirst().orElseThrow();
        WaitlistOffer rank1 = all.stream().filter(o -> o.getRank() == 1).findFirst().orElseThrow();
        assertThat(rank0.getContactId()).isEqualTo(reliable);
        assertThat(rank1.getContactId()).isEqualTo(flaky);
        assertThat(rank0.getStatus()).isEqualTo(WaitlistOffer.Status.OFFERED);

        // Both got the personalized offer SMS; the Claude prompt carried the stylist + slot.
        assertThat(sentSms).hasSize(2);
        wireMock.verify(postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Mia"))));
    }

    @Test
    void gapFill_claudeFailure_fallsBackToGenericOffer_noError() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        seedEntry(tenantId, dana, MENU_ITEM_ID);

        Integer sent = gapFillService.handle(cancelledEvent(tenantId, UUID.randomUUID(), stylistId,
                MENU_ITEM_ID, Instant.now().plus(Duration.ofHours(4)))).block();

        assertThat(sent).isEqualTo(1);
        // A generic offer still went out (mentions the stylist + a YES time-box), no error surfaced.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("Mia");
        assertThat(sentSms.get(0).body().toUpperCase()).contains("YES");
    }

    // ── 4. Inbound YES claims the slot via the normal booking path ───────────

    @Test
    void inboundYes_claimsSlot_createsBookingViaNormalPath() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        UUID freedBookingId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        seedOffer(tenantId, freedBookingId, dana, "+16185550150", slotStart, Instant.now().plus(Duration.ofMinutes(10)));

        // Inbound YES over the signed webhook → 200; the slot is claimed + a Booking created.
        postSms("+16185550150", "YES").expectStatus().isOk();

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            List<Booking> created = mongo.findAll(Booking.class).collectList().block();
            assertThat(created).hasSize(1);
            Booking b = created.get(0);
            assertThat(b.getContactId()).isEqualTo(dana);
            assertThat(b.getStaffMemberId()).isEqualTo(stylistId);
            assertThat(b.getServiceMenuItemId()).isEqualTo(MENU_ITEM_ID);
            // Mongo persists Instants at millisecond precision; the seeded slotStart carries nanos —
            // compare with a millisecond tolerance (the booking window is correct to the ms).
            assertThat(b.getScheduledStart()).isCloseTo(slotStart, within(1, java.time.temporal.ChronoUnit.MILLIS));
            // Created via the normal create() path → CONFIRMED (no deposit on this service).
            assertThat(b.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        });

        // The offer is CLAIMED; a confirmation SMS went out; WAITLIST_SLOT_CLAIMED fired.
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            WaitlistOffer o = mongo.findAll(WaitlistOffer.class).collectList().block().get(0);
            assertThat(o.getStatus()).isEqualTo(WaitlistOffer.Status.CLAIMED);
        });
        assertThat(sentSms).hasSize(1);
    }

    // ── 5. THE CROWN JEWEL — concurrent double-YES → exactly one booking + one apology ──

    @Test
    void concurrentDoubleYes_exactlyOneBooking_oneApology() {
        // Two clients with OPEN offers for the SAME freed slot. Two near-simultaneous YESs.
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        UUID flo = seedContact("Flo", "+16185550151", Set.of());
        UUID freedBookingId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        Instant expires = Instant.now().plus(Duration.ofMinutes(10));
        seedOffer(tenantId, freedBookingId, dana, "+16185550150", slotStart, expires);
        seedOffer(tenantId, freedBookingId, flo, "+16185550151", slotStart, expires);

        // Two claim Monos subscribed in parallel and joined — the deliberate concurrent race (plan ITs).
        Mono<WaitlistClaimService.ClaimOutcome> a =
                claimService.handleAffirmative(tenantId, "+16185550150").subscribeOn(reactor.core.scheduler.Schedulers.parallel());
        Mono<WaitlistClaimService.ClaimOutcome> b =
                claimService.handleAffirmative(tenantId, "+16185550151").subscribeOn(reactor.core.scheduler.Schedulers.parallel());

        var outcomes = Mono.zip(a, b).block();
        assertThat(outcomes).isNotNull();
        List<WaitlistClaimService.ClaimOutcome> results = List.of(outcomes.getT1(), outcomes.getT2());

        // Exactly one WON, exactly one LOST.
        long won = results.stream().filter(o -> o == WaitlistClaimService.ClaimOutcome.WON).count();
        long lost = results.stream().filter(o -> o == WaitlistClaimService.ClaimOutcome.LOST).count();
        assertThat(won).as("exactly one YES wins the slot").isEqualTo(1L);
        assertThat(lost).as("exactly one YES loses").isEqualTo(1L);

        // Exactly ONE Booking for the freed window — no double-book.
        List<Booking> created = mongo.findAll(Booking.class).collectList().block();
        assertThat(created).as("exactly one booking created for the contended slot").hasSize(1);

        // Exactly one offer CLAIMED, exactly one SUPERSEDED.
        List<WaitlistOffer> all = mongo.findAll(WaitlistOffer.class).collectList().block();
        long claimed = all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.CLAIMED).count();
        long superseded = all.stream().filter(o -> o.getStatus() == WaitlistOffer.Status.SUPERSEDED).count();
        assertThat(claimed).isEqualTo(1L);
        assertThat(superseded).isEqualTo(1L);

        // Two SMS total: one confirmation (winner) + one apology (loser).
        assertThat(sentSms).hasSize(2);
        long apologies = sentSms.stream().filter(s -> s.body() != null
                && s.body().toLowerCase().contains("just taken")).count();
        assertThat(apologies).as("exactly one apologetic auto-reply to the loser").isEqualTo(1L);
    }

    // ── 6. Expired offer can't be claimed ────────────────────────────────────

    @Test
    void expiredOffer_cannotBeClaimed_noBooking() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        UUID freedBookingId = UUID.randomUUID();
        Instant slotStart = Instant.now().plus(Duration.ofHours(4));
        // expiresAt in the past → not claimable.
        seedOffer(tenantId, freedBookingId, dana, "+16185550150", slotStart, Instant.now().minus(Duration.ofMinutes(1)));

        WaitlistClaimService.ClaimOutcome outcome =
                claimService.handleAffirmative(tenantId, "+16185550150").block();

        assertThat(outcome).isEqualTo(WaitlistClaimService.ClaimOutcome.NO_OPEN_OFFER);
        assertThat(mongo.findAll(Booking.class).collectList().block()).isEmpty();
        assertThat(sentSms).isEmpty();
    }

    // ── 7. Inbound STOP → opt-out ────────────────────────────────────────────

    @Test
    void inboundStop_setsOptOutTag() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());

        postSms("+16185550150", "STOP").expectStatus().isOk();

        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            Contact after = mongo.findById(dana, Contact.class).block();
            assertThat(after.getTags()).contains(RiskTieredPreventionService.SMS_OPT_OUT_TAG);
        });
    }

    // ── 8. Non-chairfill tenant is a hard no-op ──────────────────────────────

    @Test
    void nonChairfillTenant_gapFillIsHardNoOp() {
        UUID other = UUID.randomUUID();
        seedTenant(other, Set.of("salon-spa")); // no chairfill
        seedAnthropic(other);
        seedTwilio(other);
        seedMenu(other, new BigDecimal("200.00"));
        UUID otherStylist = seedStylist(other, "Lee");
        UUID pat = seedContactFor(other, "Pat", "+16185550152", Set.of());
        seedEntryFor(other, pat, MENU_ITEM_ID);
        stubOffer("(should never be called)");

        Integer sent = gapFillService.handle(cancelledEvent(other, UUID.randomUUID(), otherStylist,
                MENU_ITEM_ID, Instant.now().plus(Duration.ofHours(4)))).block();

        assertThat(sent).isEqualTo(0);
        assertThat(mongo.count(new Query(), WaitlistOffer.class).block()).isEqualTo(0L);
        assertThat(sentSms).isEmpty();
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    // ── 9. Inbound-SMS bad signature → 401/4000, zero effect ─────────────────

    @Test
    void inboundSms_badSignature_401_4000_zeroEffect() {
        UUID dana = seedContact("Dana", "+16185550150", Set.of());
        UUID freedBookingId = UUID.randomUUID();
        seedOffer(tenantId, freedBookingId, dana, "+16185550150",
                Instant.now().plus(Duration.ofHours(4)), Instant.now().plus(Duration.ofMinutes(10)));

        String path = "/public/integrations/twilio/" + tenantId + "/sms";
        MultiValueMap<String, String> form = smsForm("+16185550150", "YES");
        web.post().uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", "deadbeef_invalid")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4000);

        // Zero effect — the offer is untouched, no booking.
        assertThat(mongo.findAll(Booking.class).collectList().block()).isEmpty();
        WaitlistOffer o = mongo.findAll(WaitlistOffer.class).collectList().block().get(0);
        assertThat(o.getStatus()).isEqualTo(WaitlistOffer.Status.OFFERED);
        assertThat(sentSms).isEmpty();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private WebTestClient.ResponseSpec postSms(String from, String body) {
        String path = "/public/integrations/twilio/" + tenantId + "/sms";
        MultiValueMap<String, String> form = smsForm(from, body);
        String sig = sign(fullUrl(path), form);
        return web.post().uri(path)
                .header("X-Forwarded-Proto", "https")
                .header("X-Forwarded-Host", FORWARDED_HOST)
                .header("X-Twilio-Signature", sig)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .exchange();
    }

    private MultiValueMap<String, String> smsForm(String from, String body) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("MessageSid", "SM_test_" + UUID.randomUUID());
        form.add("From", from);
        form.add("To", BUSINESS_NUMBER);
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

    private void stubOffer(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}")));
    }

    private DomainEvent cancelledEvent(UUID tid, UUID bookingId, UUID staffId, String serviceItemId,
                                       Instant slotStart) {
        Map<String, Object> p = new HashMap<>();
        p.put("bookingId", bookingId);
        p.put("contactId", UUID.randomUUID());
        p.put("staffMemberId", staffId);
        p.put("serviceMenuItemId", serviceItemId);
        p.put("scheduledStart", slotStart);
        p.put("scheduledEnd", slotStart.plus(Duration.ofMinutes(120)));
        return DomainEvent.of(DomainEventType.BOOKING_CANCELLED, tid, bookingId, p);
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("chairfill-cf3-" + tid)
                .displayName("ChairFill CF-3 IT").status(Tenant.TenantStatus.ACTIVE)
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

    private void seedTwilio(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_cf3",
                        "authToken", AUTH_TOKEN,
                        "fromNumber", BUSINESS_NUMBER)))
                .build()).block();
    }

    private void seedMenu(UUID tid, BigDecimal price) {
        mongo.save(ServiceMenu.builder()
                .id(UUID.randomUUID()).tenantId(tid).name("Main Menu")
                .services(List.of(ServiceMenuItem.builder()
                        .id(MENU_ITEM_ID).name("Balayage").durationMinutes(120).price(price).build()))
                .build()).block();
    }

    private UUID seedStylist(UUID tid, String name) {
        return mongo.save(StaffMember.builder()
                .id(UUID.randomUUID()).tenantId(tid).displayName(name).active(true).build())
                .block().getId();
    }

    private UUID seedContact(String firstName, String phone, Set<String> tags) {
        return seedContactFor(tenantId, firstName, phone, tags);
    }

    private UUID seedContactFor(UUID tid, String firstName, String phone, Set<String> tags) {
        return mongo.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(tags)
                .build()).block().getId();
    }

    private UUID seedEntry(UUID tid, UUID contactId, String serviceItemId) {
        return seedEntryFor(tid, contactId, serviceItemId);
    }

    private UUID seedEntryFor(UUID tid, UUID contactId, String serviceItemId) {
        return mongo.save(WaitlistEntry.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .contactId(contactId)
                .serviceMenuItemId(serviceItemId)
                .smsOptIn(true)
                .status(WaitlistEntry.Status.OPEN)
                .build()).block().getId();
    }

    private void seedOffer(UUID tid, UUID freedBookingId, UUID contactId, String phone,
                           Instant slotStart, Instant expiresAt) {
        mongo.save(WaitlistOffer.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .freedBookingId(freedBookingId)
                .contactId(contactId)
                .contactPhone(phone)
                .staffMemberId(stylistId)
                .serviceMenuItemId(MENU_ITEM_ID)
                .serviceMenuItemName("Balayage")
                .slotStart(slotStart)
                .slotEnd(slotStart.plus(Duration.ofMinutes(120)))
                .rank(0)
                .status(WaitlistOffer.Status.OFFERED)
                .sentAt(Instant.now())
                .expiresAt(expiresAt)
                .build()).block();
    }

    private UUID seedBooking(UUID tid, UUID contactId, UUID staffId, BookingStatus status, Instant start) {
        return mongo.save(Booking.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .contactId(contactId).staffMemberId(staffId)
                .serviceMenuItemId(MENU_ITEM_ID).serviceMenuItemName("Balayage")
                .scheduledStart(start).scheduledEnd(start.plus(Duration.ofMinutes(120)))
                .status(status)
                .build()).block().getId();
    }

    private void seedTerminal(UUID tid, UUID contactId, BookingStatus status, Instant start) {
        mongo.save(Booking.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .contactId(contactId).staffMemberId(stylistId)
                .serviceMenuItemId(MENU_ITEM_ID).serviceMenuItemName("Balayage")
                .scheduledStart(start).scheduledEnd(start.plus(Duration.ofMinutes(120)))
                .status(status)
                .build()).block();
    }
}
