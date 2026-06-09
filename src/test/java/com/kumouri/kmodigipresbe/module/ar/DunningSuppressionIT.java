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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * AR-4 — {@code DunningSuppressionIT}: proves both sides of the AR-4 promise-to-pay
 * suppression guard in {@link DunningDispatchService}.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li><strong>Suppressed</strong> — a SENT invoice + an ACTIVE {@link PromiseToPay} with
 *       {@code promisedDate >= today} → publish {@code INVOICE_OVERDUE_D7} → assert
 *       <strong>zero</strong> {@code TwilioSmsService.sendSms} calls (the guard suppresses);
 *       zero Anthropic requests; zero Stripe traffic;</li>
 *   <li><strong>Not suppressed — no promise</strong> — same invoice, no promise → the dunning
 *       send proceeds (exactly one SMS);</li>
 *   <li><strong>Not suppressed — expired promise</strong> — an ACTIVE promise whose
 *       {@code promisedDate} is in the past (yesterday) → the guard does NOT suppress
 *       (the promise lapsed), the send proceeds.</li>
 * </ol>
 *
 * <p>The AT test uses the fixed clock (2026-06-09) so "today / yesterday / tomorrow" are exact.
 * Anthropic → WireMock; Twilio + Stripe → {@code @MockitoBean} (the AR-3 pattern).
 *
 * <p>Shard-safe: self-clean {@code @BeforeEach}; no extra shard / context isolation needed.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, DunningSuppressionIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.ar.enabled=true",
        "kmosf.modules.ar.initial-delay-ms=3600000",
        "kmosf.modules.ar.interval-ms=3600000",
        "kmosf.modules.ar.dunning-draft-model=claude-haiku-4-5"
})
class DunningSuppressionIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final LocalDate TODAY = LocalDate.ofInstant(NOW, ZoneOffset.UTC);

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-suppression-fake";
    private static final String CUSTOMER_PHONE = "+16185550199";
    private static final String STRIPE_PAY_LINK = "https://pay.stripe.test/pl_suppression_test";

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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
    @Autowired PromiseToPayRepository promisesToPay;

    @MockitoBean TwilioSmsService twilioSmsService;
    @MockitoBean StripeCheckoutService stripeCheckoutService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private UUID contactId;
    private UUID invoiceId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        stubAnthropicReply("Hi — your invoice is overdue. Pay now: " + STRIPE_PAY_LINK);

        mongo.remove(new Query(), Invoice.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), PromiseToPay.class).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });
        org.mockito.Mockito.when(stripeCheckoutService.createCheckoutForInvoice(
                        org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.eq(StripeCheckoutService.Mode.PAYMENT_LINK)))
                .thenReturn(Mono.just(new StripeCheckoutService.CheckoutResult(
                        STRIPE_PAY_LINK, StripeCheckoutService.Mode.PAYMENT_LINK.name(),
                        UUID.randomUUID())));

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("ar-suppression-it-" + tenantId)
                .displayName("AR Suppression IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("ar"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        // Seed a contact with a phone (so the send can reach the SMS path if unsuppressed).
        contactId = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(contactId).tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName("Morgan").displayName("Morgan")
                .phones(List.of(PhoneNumber.builder().number(CUSTOMER_PHONE).label("mobile").build()))
                .build()).block();

        // Seed the invoice (SENT, past-due).
        invoiceId = UUID.randomUUID();
        mongo.save(Invoice.builder()
                .id(invoiceId).tenantId(tenantId)
                .invoiceNumber("INV-SUPP-001").status(Invoice.Status.SENT)
                .contactId(contactId)
                .currency("USD").lineItems(List.of())
                .subtotal(new BigDecimal("300.00"))
                .total(new BigDecimal("300.00"))
                .balance(new BigDecimal("300.00"))
                .issuedAt(TODAY.minusDays(60)).dueAt(TODAY.minusDays(7))
                .build()).block();
    }

    // ── 1. Active future promise → suppressed (zero sends) ───────────────────────────────────

    @Test
    void activeFutureDatedPromise_suppressesDunning() {
        // Seed an ACTIVE promise whose promisedDate is tomorrow (future).
        seedPromise(PromiseToPay.Status.ACTIVE, TODAY.plusDays(1));

        dunningDispatchService.handle(overdueEvent(DomainEventType.INVOICE_OVERDUE_D7, 7)).block();

        assertThat(sentSms).as("dunning SMS should be suppressed").isEmpty();
        // Zero Anthropic and Stripe traffic.
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
        org.mockito.Mockito.verify(stripeCheckoutService, org.mockito.Mockito.never())
                .createCheckoutForInvoice(org.mockito.ArgumentMatchers.any(),
                        org.mockito.ArgumentMatchers.any());
    }

    @Test
    void activeTodayPromise_suppressesDunning() {
        // promisedDate = TODAY should also suppress (today-or-future).
        seedPromise(PromiseToPay.Status.ACTIVE, TODAY);

        dunningDispatchService.handle(overdueEvent(DomainEventType.INVOICE_OVERDUE_D7, 7)).block();

        assertThat(sentSms).as("dunning SMS should be suppressed for today-dated promise").isEmpty();
    }

    // ── 2. No promise → sends ────────────────────────────────────────────────────────────────

    @Test
    void noPromise_dunningProceeds() {
        // No promise seeded — the guard should not suppress.
        dunningDispatchService.handle(overdueEvent(DomainEventType.INVOICE_OVERDUE_D7, 7)).block();

        assertThat(sentSms).as("dunning SMS should send when no promise exists").hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(CUSTOMER_PHONE);
    }

    // ── 3. Expired (past-dated) ACTIVE promise → does NOT suppress ───────────────────────────

    @Test
    void expiredActivePromise_doesNotSuppress_dunningProceeds() {
        // Promise date was yesterday — the promise lapsed, dunning should proceed.
        seedPromise(PromiseToPay.Status.ACTIVE, TODAY.minusDays(1));

        dunningDispatchService.handle(overdueEvent(DomainEventType.INVOICE_OVERDUE_D7, 7)).block();

        assertThat(sentSms).as("dunning SMS should NOT be suppressed for an expired promise").hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(CUSTOMER_PHONE);
    }

    @Test
    void keptOrCancelledPromise_doesNotSuppress() {
        // A KEPT or CANCELLED promise is not ACTIVE — should not suppress.
        seedPromise(PromiseToPay.Status.KEPT, TODAY.plusDays(3));  // kept but status != ACTIVE

        dunningDispatchService.handle(overdueEvent(DomainEventType.INVOICE_OVERDUE_D7, 7)).block();

        assertThat(sentSms).as("a KEPT promise should not suppress dunning").hasSize(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private DomainEvent overdueEvent(String type, long daysOverdue) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoiceId.toString());
        payload.put("contactId", contactId.toString());
        payload.put("daysOverdue", daysOverdue);
        payload.put("balance", new BigDecimal("300.00"));
        payload.put("currency", "USD");
        return DomainEvent.of(type, tenantId, invoiceId, payload);
    }

    private void seedPromise(PromiseToPay.Status status, LocalDate promisedDate) {
        promisesToPay.save(PromiseToPay.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .invoiceId(invoiceId).contactId(contactId)
                .promisedDate(promisedDate)
                .status(status)
                .build()).block();
    }

    private void stubAnthropicReply(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_supp\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                                + "\"usage\":{\"input_tokens\":100,\"output_tokens\":25}}")));
    }
}
