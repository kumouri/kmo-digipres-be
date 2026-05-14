package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 10d — verifies the {@code INVOICE_FINALIZED} → push-to-QBO flow against
 * WireMock standing in for Intuit. No Spring context — wires the sync component
 * with mocked repositories and a real DomainEventPublisher.
 */
class QuickBooksInvoiceSyncTest {

    private WireMockServer wireMock;
    private IntegrationConnectionRepository connections;
    private InvoiceRepository invoices;
    private DomainEventPublisher events;
    private QuickBooksInvoiceSync sync;
    private QuickBooksOAuthService oauth;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        connections = mock(IntegrationConnectionRepository.class);
        invoices = mock(InvoiceRepository.class);
        events = new DomainEventPublisher();

        QuickBooksProperties props = new QuickBooksProperties();
        props.setEnabled(true);
        props.setClientId("client-id");
        props.setClientSecret("client-secret");
        props.setRedirectUri("http://localhost/callback");
        props.setApiBaseUrl(wireMock.baseUrl());
        props.setOauthBaseUrl(wireMock.baseUrl());
        props.setStateSigningSecret("test-state-secret");

        oauth = new QuickBooksOAuthService(
                props, connections, WebClient.builder(), new ObjectMapper());

        sync = new QuickBooksInvoiceSync(
                events, connections, invoices, oauth,
                WebClient.builder(), new ObjectMapper(), props,
                CircuitBreakerRegistry.ofDefaults());
        sync.subscribe();
    }

    @AfterEach
    void stop() {
        sync.unsubscribe();
        wireMock.stop();
    }

    @Test
    void invoiceFinalized_pushesToIntuit_andStampsExternalRef() {
        UUID tenantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        String realmId = "9341452346000000";

        IntegrationConnection conn = IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("quickbooks")
                .secrets(new HashMap<>(Map.of(
                        "accessToken", "valid-access-token",
                        "refreshToken", "refresh",
                        "tokenExpiresAt", Instant.now().plusSeconds(3600).toString())))
                .config(new HashMap<>(Map.of("realmId", realmId)))
                .build();
        when(connections.findByTenantIdAndProvider(eq(tenantId), eq("quickbooks")))
                .thenReturn(Mono.just(conn));

        Invoice invoice = Invoice.builder()
                .id(invoiceId)
                .tenantId(tenantId)
                .invoiceNumber("INV-100")
                .status(Invoice.Status.SENT)
                .currency("USD")
                .lineItems(List.of(LineItem.builder()
                        .description("Service call")
                        .quantity(BigDecimal.ONE)
                        .unitPrice(new BigDecimal("125.00"))
                        .lineTotal(new BigDecimal("125.00"))
                        .build()))
                .total(new BigDecimal("125.00"))
                .dueAt(LocalDate.of(2026, 6, 1))
                .externalRefs(new HashMap<>())
                .build();
        when(invoices.findById(eq(invoiceId))).thenReturn(Mono.just(invoice));
        when(invoices.save(any(Invoice.class)))
                .thenAnswer(inv -> Mono.just((Invoice) inv.getArgument(0)));

        wireMock.stubFor(post(urlPathEqualTo("/v3/company/" + realmId + "/invoice"))
                .withHeader("Authorization", equalTo("Bearer valid-access-token"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"Invoice\":{\"Id\":\"qbo-inv-42\","
                                + "\"DocNumber\":\"INV-100\",\"TotalAmt\":125.00},"
                                + "\"time\":\"2026-05-14T20:00:00.000Z\"}")));

        events.publish(DomainEvent.of(
                DomainEventType.INVOICE_FINALIZED, tenantId, invoiceId,
                Map.of("invoiceId", invoiceId.toString(),
                        "totalAmount", new BigDecimal("125.00"),
                        "currency", "USD")));

        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                wireMock.verify(postRequestedFor(
                        urlPathEqualTo("/v3/company/" + realmId + "/invoice"))));

        ArgumentCaptor<Invoice> savedCap = ArgumentCaptor.forClass(Invoice.class);
        verify(invoices, atLeastOnce()).save(savedCap.capture());
        Invoice saved = savedCap.getValue();
        assertThat(saved.getExternalRefs()).containsEntry("quickbooks", "qbo-inv-42");
    }

    @Test
    void invoiceFinalized_withoutQboConnection_isSkipped() {
        UUID tenantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        when(connections.findByTenantIdAndProvider(eq(tenantId), eq("quickbooks")))
                .thenReturn(Mono.empty());

        events.publish(DomainEvent.of(
                DomainEventType.INVOICE_FINALIZED, tenantId, invoiceId,
                Map.of("invoiceId", invoiceId.toString())));

        // Give the subscriber a chance — there should be no API call and no save.
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/v3/company/realm/invoice")));
        verify(invoices, org.mockito.Mockito.never()).save(any(Invoice.class));
    }

    @Test
    void alreadySyncedInvoice_isNoOp() {
        UUID tenantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        String realmId = "9999";

        IntegrationConnection conn = IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("quickbooks")
                .secrets(new HashMap<>(Map.of(
                        "accessToken", "tok",
                        "refreshToken", "rt",
                        "tokenExpiresAt", Instant.now().plusSeconds(3600).toString())))
                .config(new HashMap<>(Map.of("realmId", realmId)))
                .build();
        when(connections.findByTenantIdAndProvider(eq(tenantId), eq("quickbooks")))
                .thenReturn(Mono.just(conn));

        Invoice invoice = Invoice.builder()
                .id(invoiceId)
                .tenantId(tenantId)
                .status(Invoice.Status.SENT)
                .currency("USD")
                .total(new BigDecimal("50.00"))
                .lineItems(List.of())
                .externalRefs(new HashMap<>(Map.of("quickbooks", "already-synced-id")))
                .build();
        when(invoices.findById(eq(invoiceId))).thenReturn(Mono.just(invoice));

        events.publish(DomainEvent.of(
                DomainEventType.INVOICE_FINALIZED, tenantId, invoiceId, Map.of()));

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // No call to Intuit, no save.
        assertThat(wireMock.getAllServeEvents()).isEmpty();
        verify(invoices, org.mockito.Mockito.never()).save(any(Invoice.class));
    }
}
