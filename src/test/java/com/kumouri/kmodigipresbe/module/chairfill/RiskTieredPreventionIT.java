package com.kumouri.kmodigipresbe.module.chairfill;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.chairfill.automation.ReminderLog;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

/**
 * ChairFill CF-2 — RiskTieredPreventionIT: drives the {@link RiskTieredPreventionService} subscriber
 * end-to-end via its visible-for-test {@code handle(event)} (the {@code CoverageNudgeJob.nudgeDueOnce()}
 * posture — deterministic, no live event-bus race). Anthropic goes to WireMock via
 * {@code kmosf.ai.anthropic.base-url} (the {@code GbpReplyDraftServiceIT} pattern); {@link TwilioSmsService}
 * is the {@code @MockitoBean} capture seam (its base URL is not config-driven — the {@code CoverageNudgeIT}
 * precedent).
 *
 * <h2>Coverage (plan CF-2 ITs)</h2>
 * <ol>
 *   <li>HIGH booking ⇒ deposit required (via the reused {@code SalonBookingService.requireDepositNow}
 *       path: {@code depositRequired} flips, a DRAFT {@code Invoice} exists, status PENDING_DEPOSIT) +
 *       an extra-confirmation SMS attempted, the Claude prompt carrying the stylist + service;</li>
 *   <li>LOW booking ⇒ exactly ONE reminder SMS, NO deposit, the prompt carrying the stylist + service;</li>
 *   <li>best-effort fallback ⇒ a WireMock Anthropic 500 still sends a GENERIC reminder (no error,
 *       {@code personalized=false} in the ledger);</li>
 *   <li>a non-chairfill tenant is a no-op (no SMS, no deposit, no ledger);</li>
 *   <li>TCPA: an opted-out contact ({@value RiskTieredPreventionService#SMS_OPT_OUT_TAG} tag) gets NO
 *       SMS — consent gate; and a re-fired event for the same booking is idempotent (zero duplicate).</li>
 * </ol>
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic → WireMock (never a real host); Twilio → a mock bean. No outbound network.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        // Force the cheaper Haiku cost-estimate branch (any model works for the WireMock stub).
        "kmosf.chairfill.reminder-draft-model=claude-haiku-4-5",
        // Deterministic deposit amount: 25% of a $200 service = $50.00.
        "kmosf.chairfill.prevention.deposit-rate=0.25",
        "kmosf.chairfill.prevention.deposit-min=20.00",
        // A generous frequency cap so the per-test single-booking flow isn't capped (the opt-out
        // test asserts the consent gate; a dedicated low-cap is set inline where needed).
        "kmosf.chairfill.prevention.max-per-contact-per-window=5",
        "kmosf.chairfill.prevention.window-hours=24"
})
class RiskTieredPreventionIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-chairfill-fake";
    private static final String CLIENT_PHONE = "+16185550199";
    private static final String MENU_ITEM_ID = "balayage";

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

    @Autowired RiskTieredPreventionService preventionService;
    @Autowired TenantRepository tenants;
    @Autowired BookingRepository bookings;
    @Autowired ServiceMenuRepository menus;
    @Autowired StaffMemberRepository staff;
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
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), ReminderLog.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("salon-spa", "chairfill"));
        seedAnthropic(tenantId);
        seedMenu(tenantId, new BigDecimal("200.00"));
        stylistId = seedStylist(tenantId, "Mia");
    }

    private DomainEvent riskEvent(UUID tid, UUID bookingId, UUID contactId, String tier) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", bookingId);
        payload.put("contactId", contactId);
        payload.put("staffMemberId", stylistId);
        payload.put("riskTier", tier);
        payload.put("riskScore", NoShowRisk.TIER_HIGH.equals(tier) ? 0.82 : 0.2);
        payload.put("source", NoShowRisk.SOURCE_MODEL);
        return DomainEvent.of(DomainEventType.BOOKING_RISK_SCORED, tid, bookingId, payload);
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    void highRisk_requiresDeposit_andSendsPersonalizedConfirmation() {
        stubReply("Hi Jane! Just confirming your balayage with Mia on Friday — reply YES to lock it in.");

        UUID contactId = seedContact("Jane", CLIENT_PHONE, Set.of());
        UUID bookingId = seedUpcoming(tenantId, contactId, BookingStatus.CONFIRMED,
                Instant.now().plus(Duration.ofDays(2)), false, null);

        preventionService.handle(riskEvent(tenantId, bookingId, contactId, NoShowRisk.TIER_HIGH)).block();

        // Deposit was required via the reused path: flag flips, a DRAFT invoice exists, status reverts.
        Booking after = loadBooking(bookingId);
        assertThat(after.isDepositRequired()).isTrue();
        assertThat(after.getDepositInvoiceId()).isNotNull();
        assertThat(after.getStatus()).isEqualTo(BookingStatus.PENDING_DEPOSIT);
        assertThat(after.getDepositAmount()).isEqualByComparingTo("50.00"); // 25% of $200

        Invoice inv = mongo.findById(after.getDepositInvoiceId(), Invoice.class).block();
        assertThat(inv).isNotNull();
        assertThat(inv.getStatus()).isEqualTo(Invoice.Status.DRAFT);
        assertThat(inv.getTotal()).isEqualByComparingTo("50.00");

        // Exactly one (confirmation) SMS attempted, carrying the personalized copy.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(CLIENT_PHONE);
        assertThat(sentSms.get(0).body()).contains("Mia");

        // The Claude prompt carried the stylist + the service (proves personalization + §7 base-url).
        wireMock.verify(postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Mia")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Balayage"))));

        // Ledger stamped personalized + HIGH.
        ReminderLog log = mongo.findAll(ReminderLog.class).collectList().block().get(0);
        assertThat(log.isPersonalized()).isTrue();
        assertThat(log.isDepositRequired()).isTrue();
        assertThat(log.getRiskTier()).isEqualTo(NoShowRisk.TIER_HIGH);
    }

    @Test
    void lowRisk_sendsSinglePersonalizedReminder_noDeposit() {
        stubReply("Hi Dana! See you Friday for your balayage with Mia — can't wait!");

        UUID contactId = seedContact("Dana", CLIENT_PHONE, Set.of());
        UUID bookingId = seedUpcoming(tenantId, contactId, BookingStatus.CONFIRMED,
                Instant.now().plus(Duration.ofDays(2)), false, null);

        preventionService.handle(riskEvent(tenantId, bookingId, contactId, NoShowRisk.TIER_LOW)).block();

        // No deposit for a LOW-risk booking.
        Booking after = loadBooking(bookingId);
        assertThat(after.isDepositRequired()).isFalse();
        assertThat(after.getDepositInvoiceId()).isNull();
        assertThat(after.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(mongo.count(new Query(), Invoice.class).block()).isEqualTo(0L);

        // Exactly one reminder SMS, personalized.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("Mia");

        wireMock.verify(postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Mia")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("Balayage"))));
    }

    @Test
    void claudeFailure_fallsBackToGenericReminder_noError() {
        // Upstream 500 -> ReminderCopyService surfaces 1202 -> the subscriber degrades to generic.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        UUID contactId = seedContact("Sam", CLIENT_PHONE, Set.of());
        UUID bookingId = seedUpcoming(tenantId, contactId, BookingStatus.CONFIRMED,
                Instant.now().plus(Duration.ofDays(2)), false, null);

        // Must NOT throw — best-effort.
        preventionService.handle(riskEvent(tenantId, bookingId, contactId, NoShowRisk.TIER_LOW)).block();

        // A generic reminder still went out (mentions the stylist via the template), no error surfaced.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).body()).contains("Mia");
        assertThat(sentSms.get(0).body()).contains("Sam");

        // Ledger records the fallback (personalized=false).
        ReminderLog log = mongo.findAll(ReminderLog.class).collectList().block().get(0);
        assertThat(log.isPersonalized()).isFalse();
    }

    @Test
    void nonChairfillTenant_isHardNoOp_evenWhenEventFired() {
        // A salon tenant WITHOUT chairfill in enabledModules. Even if a BOOKING_RISK_SCORED event is
        // fired for it (CF-1 never would — moduleDisabled_skipsTenant proves that — but this is the
        // defense-in-depth guarantee), the subscriber re-checks module membership and hard no-ops:
        // no deposit, no SMS, no ledger. Blast radius zero (HARD GATE 3).
        UUID otherTenant = UUID.randomUUID();
        seedTenant(otherTenant, Set.of("salon-spa")); // no chairfill
        seedAnthropic(otherTenant);
        seedMenu(otherTenant, new BigDecimal("200.00"));
        UUID otherStylist = seedStylist(otherTenant, "Lee");
        UUID contactId = seedContactFor(otherTenant, "Pat", CLIENT_PHONE, Set.of());
        UUID bookingId = seedUpcomingFor(otherTenant, contactId, otherStylist, BookingStatus.CONFIRMED,
                Instant.now().plus(Duration.ofDays(2)), false, null);
        stubReply("(should never be called)");

        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", bookingId);
        payload.put("contactId", contactId);
        payload.put("staffMemberId", otherStylist);
        payload.put("riskTier", NoShowRisk.TIER_HIGH);
        DomainEvent e = DomainEvent.of(DomainEventType.BOOKING_RISK_SCORED, otherTenant, bookingId, payload);

        // Fire it directly — must return cleanly with zero side effects.
        preventionService.handle(e).block();

        Booking after = loadBooking(bookingId);
        assertThat(after.isDepositRequired()).isFalse();
        assertThat(after.getDepositInvoiceId()).isNull();
        assertThat(after.getStatus()).isEqualTo(BookingStatus.CONFIRMED);
        assertThat(sentSms).isEmpty();
        assertThat(mongo.count(new Query(), ReminderLog.class).block()).isEqualTo(0L);
        assertThat(mongo.count(new Query(), Invoice.class).block()).isEqualTo(0L);
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    @Test
    void optedOutContact_getsNoSms_andReFiredEventIsIdempotent() {
        stubReply("Hi! Reminder about your appointment.");

        // Opted-out contact (STOP) -> the consent gate skips: no SMS, no ledger.
        UUID optedOut = seedContact("Optout", CLIENT_PHONE,
                Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG));
        UUID optedOutBooking = seedUpcoming(tenantId, optedOut, BookingStatus.CONFIRMED,
                Instant.now().plus(Duration.ofDays(2)), false, null);

        preventionService.handle(riskEvent(tenantId, optedOutBooking, optedOut, NoShowRisk.TIER_LOW)).block();

        assertThat(sentSms).isEmpty();
        assertThat(mongo.count(new Query(), ReminderLog.class).block()).isEqualTo(0L);

        // A reachable contact -> one SMS; a SECOND handle of the same booking is idempotent (zero dup).
        UUID reachable = seedContact("Dana", CLIENT_PHONE, Set.of());
        UUID bookingId = seedUpcoming(tenantId, reachable, BookingStatus.CONFIRMED,
                Instant.now().plus(Duration.ofDays(3)), false, null);

        DomainEvent ev = riskEvent(tenantId, bookingId, reachable, NoShowRisk.TIER_LOW);
        preventionService.handle(ev).block();
        assertThat(sentSms).hasSize(1);

        preventionService.handle(ev).block(); // re-fire
        assertThat(sentSms).hasSize(1); // still exactly one — ledger-insert-FIRST dedupe
        Long ledgerForBooking = mongo.count(
                new Query(org.springframework.data.mongodb.core.query.Criteria.where("bookingId").is(bookingId)),
                ReminderLog.class).block();
        assertThat(ledgerForBooking).isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubReply(String replyText) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + replyText + "\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}")));
    }

    private Booking loadBooking(UUID id) {
        return mongo.findById(id, Booking.class).block();
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("chairfill-cf2-" + tid)
                .displayName("ChairFill CF-2 IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new BigDecimal("5.00")) // non-zero so the budget gate passes
                .build()).block();
    }

    private void seedAnthropic(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private void seedMenu(UUID tid, BigDecimal price) {
        mongo.save(ServiceMenu.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .name("Main Menu")
                .services(List.of(ServiceMenuItem.builder()
                        .id(MENU_ITEM_ID).name("Balayage").durationMinutes(120).price(price).build()))
                .build()).block();
    }

    private UUID seedStylist(UUID tid, String name) {
        StaffMember s = StaffMember.builder()
                .id(UUID.randomUUID()).tenantId(tid).displayName(name).active(true).build();
        return mongo.save(s).block().getId();
    }

    private UUID seedContact(String firstName, String phone, Set<String> tags) {
        return seedContactFor(tenantId, firstName, phone, tags);
    }

    private UUID seedContactFor(UUID tid, String firstName, String phone, Set<String> tags) {
        Contact c = Contact.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(tags)
                .build();
        return mongo.save(c).block().getId();
    }

    private UUID seedUpcoming(UUID tid, UUID contactId, BookingStatus status,
                             Instant start, boolean depositRequired, BigDecimal depositAmount) {
        return seedUpcomingFor(tid, contactId, stylistId, status, start, depositRequired, depositAmount);
    }

    private UUID seedUpcomingFor(UUID tid, UUID contactId, UUID staffId, BookingStatus status,
                                 Instant start, boolean depositRequired, BigDecimal depositAmount) {
        Booking b = Booking.builder()
                .id(UUID.randomUUID())
                .tenantId(tid)
                .contactId(contactId)
                .staffMemberId(staffId)
                .serviceMenuItemId(MENU_ITEM_ID)
                .serviceMenuItemName("Balayage")
                .scheduledStart(start)
                .scheduledEnd(start.plus(Duration.ofMinutes(120)))
                .status(status)
                .depositRequired(depositRequired)
                .depositAmount(depositAmount)
                .build();
        return mongo.save(b).block().getId();
    }
}
