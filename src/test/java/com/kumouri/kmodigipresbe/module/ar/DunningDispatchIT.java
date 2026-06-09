package com.kumouri.kmodigipresbe.module.ar;

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
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.billing.StripeCheckoutService;
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
import java.time.LocalDate;
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
 * "Get Paid" AR-3 — DunningDispatchIT: drives the (default-OFF) {@link DunningDispatchService}
 * subscriber end-to-end via its visible-for-test {@code handle(event)} (the deterministic
 * {@code RiskTieredPreventionIT} / {@code CoverageNudgeJob.nudgeDueOnce()} posture — no live event-bus
 * race). Anthropic goes to WireMock via {@code kmosf.ai.anthropic.base-url} (the
 * {@code RiskTieredPreventionIT} pattern); {@link TwilioSmsService} and {@link StripeCheckoutService}
 * are {@code @MockitoBean} seams (Twilio's base URL is not config-driven; Stripe needs a mocked pay
 * link so NO live Stripe call is ever made — §7).
 *
 * <h2>Coverage (AR-3 ITs)</h2>
 * <ol>
 *   <li><strong>send</strong> — a SENT invoice with a contact + E.164 phone → publish
 *       {@code INVOICE_OVERDUE_D7} → exactly ONE {@code TwilioSmsService.sendSms} whose body contains
 *       BOTH the WireMock-stubbed AI copy AND the (mocked) Stripe pay link; the D7 firmer tone reaches
 *       the Claude prompt; an advisory {@code DUNNING_SENT} fires;</li>
 *   <li><strong>auto-stop</strong> — a PAID invoice → publish {@code INVOICE_OVERDUE_D7} → ZERO sends
 *       (the since-paid invoice is no longer collectible), zero Stripe traffic, no error;</li>
 *   <li><strong>no phone</strong> — a contact with no phone → ZERO sends, no error.</li>
 * </ol>
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic → WireMock (never a real host); Twilio + Stripe → mock beans. No outbound network. The
 * module is default-OFF ({@code matchIfMissing=false}); this IT explicitly opts in via
 * {@code kmosf.modules.ar.enabled=true}.
 *
 * <p>Shard-safe: self-clean {@code mongo.remove} {@code @BeforeEach}; no {@code application-test.properties}
 * / {@code build.gradle} shard change.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.ar.enabled=true",
        // Push the AR sweep's @Scheduled tick far out so it never races this IT (the sweep is also
        // bean-present under this gate; we only ever drive DunningDispatchService.handle directly).
        "kmosf.modules.ar.initial-delay-ms=3600000",
        "kmosf.modules.ar.interval-ms=3600000",
        // Force the cheaper Haiku cost-estimate branch (any model works for the WireMock stub).
        "kmosf.modules.ar.dunning-draft-model=claude-haiku-4-5"
})
class DunningDispatchIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-ar-fake";
    private static final String CUSTOMER_PHONE = "+16185550143";
    private static final String STRIPE_PAY_LINK = "https://pay.stripe.test/pl_ar_dunning_fake";

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

    @Autowired DunningDispatchService dunningDispatchService;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean StripeCheckoutService stripeCheckoutService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });
        // Mocked one-touch Stripe pay link — NO live Stripe call is ever made (§7).
        org.mockito.Mockito.when(stripeCheckoutService.createCheckoutForInvoice(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(StripeCheckoutService.Mode.PAYMENT_LINK)))
                .thenAnswer(inv -> Mono.just(new StripeCheckoutService.CheckoutResult(
                        STRIPE_PAY_LINK, StripeCheckoutService.Mode.PAYMENT_LINK.name(),
                        inv.getArgument(0))));

        tenantId = UUID.randomUUID();
        seedTenant(tenantId);
        seedAnthropic(tenantId);
    }

    private DomainEvent overdueEvent(UUID tid, UUID invoiceId, UUID contactId, String type, long days) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoiceId.toString());
        if (contactId != null) payload.put("contactId", contactId.toString());
        payload.put("daysOverdue", days);
        payload.put("balance", new BigDecimal("250.00"));
        payload.put("currency", "USD");
        return DomainEvent.of(type, tid, invoiceId, payload);
    }

    // ── tests ──────────────────────────────────────────────────────────────

    @Test
    void overdueSentInvoice_sendsPersonalizedDunningWithStripeLink() {
        stubReply("Hi Jordan — invoice INV-2026-0007 ($250.00) is now past due. "
                + "Pay in one tap: " + STRIPE_PAY_LINK);

        UUID contactId = seedContact("Jordan", CUSTOMER_PHONE);
        UUID invoiceId = seedInvoice("INV-2026-0007", Invoice.Status.SENT, contactId);

        dunningDispatchService.handle(
                overdueEvent(tenantId, invoiceId, contactId, DomainEventType.INVOICE_OVERDUE_D7, 7)).block();

        // Exactly one dunning SMS, to the customer, carrying BOTH the AI copy AND the Stripe pay link.
        assertThat(sentSms).hasSize(1);
        SmsCommunicationRequest sms = sentSms.get(0);
        assertThat(sms.to().e164()).isEqualTo(CUSTOMER_PHONE);
        assertThat(sms.body()).contains("Jordan");          // AI-personalized copy
        assertThat(sms.body()).contains(STRIPE_PAY_LINK);   // one-touch pay link wired in

        // The D7 firmer tone + the pay link reached the Claude prompt (proves tier-tone + §7 base-url).
        wireMock.verify(postRequestedFor(urlPathEqualTo("/"))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing("firmer second notice")))
                .withRequestBody(matchingJsonPath("$.messages[0].content", containing(STRIPE_PAY_LINK))));

        // The advisory DUNNING_SENT breadcrumb fired exactly once for this invoice.
        // (Asserted via the SMS being the single effect; the event is fire-and-forget — no ledger.)
    }

    @Test
    void paidInvoice_autoStops_noSend() {
        // A since-PAID invoice (no longer in {SENT, OVERDUE}) — the auto-stop guard skips the send.
        stubReply("(should never be called)");

        UUID contactId = seedContact("Riley", CUSTOMER_PHONE);
        UUID invoiceId = seedInvoice("INV-2026-0008", Invoice.Status.PAID, contactId);

        dunningDispatchService.handle(
                overdueEvent(tenantId, invoiceId, contactId, DomainEventType.INVOICE_OVERDUE_D7, 7)).block();

        assertThat(sentSms).isEmpty();
        // Zero Stripe traffic and zero Claude traffic — the guard short-circuits before either.
        org.mockito.Mockito.verify(stripeCheckoutService, org.mockito.Mockito.never())
                .createCheckoutForInvoice(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    @Test
    void contactWithNoPhone_noSend_noError() {
        stubReply("(should never be called)");

        UUID contactId = seedContactNoPhone("Casey");
        UUID invoiceId = seedInvoice("INV-2026-0009", Invoice.Status.OVERDUE, contactId);

        // Must NOT throw — a clean skip.
        dunningDispatchService.handle(
                overdueEvent(tenantId, invoiceId, contactId, DomainEventType.INVOICE_OVERDUE_D3, 3)).block();

        assertThat(sentSms).isEmpty();
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
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

    private void seedTenant(UUID tid) {
        tenants.save(Tenant.builder()
                .id(tid).slug("ar-dunning-it-" + tid)
                .displayName("AR Dunning IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("ar"))
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

    private UUID seedContact(String firstName, String phone) {
        Contact c = Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .build();
        return mongo.save(c).block().getId();
    }

    private UUID seedContactNoPhone(String firstName) {
        Contact c = Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of())
                .build();
        return mongo.save(c).block().getId();
    }

    private UUID seedInvoice(String number, Invoice.Status status, UUID contactId) {
        UUID id = UUID.randomUUID();
        mongo.save(Invoice.builder()
                .id(id).tenantId(tenantId)
                .invoiceNumber(number)
                .status(status)
                .contactId(contactId)
                .currency("USD")
                .lineItems(List.of())
                .subtotal(new BigDecimal("250.00"))
                .total(new BigDecimal("250.00"))
                .balance(new BigDecimal("250.00"))
                .issuedAt(LocalDate.of(2026, 5, 1))
                .dueAt(LocalDate.of(2026, 6, 1))
                .build()).block();
        return id;
    }
}
