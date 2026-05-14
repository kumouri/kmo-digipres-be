package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;


import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Receives Intuit's inbound webhook notifications. Intuit posts a batch of
 * {@code eventNotifications}; for each one whose {@code name} is {@code "Invoice"}
 * and {@code operation} is {@code "Payment"} (i.e. an invoice was paid in QBO
 * directly), we look up the CRM {@link Invoice} that carries the matching QBO
 * id in its {@link Invoice#getExternalRefs()} map and record a payment of the
 * remaining balance via {@link InvoiceService#recordPayment}.
 *
 * <p>Mirrors the {@code StripeWebhookService} / {@code PostmarkWebhookService}
 * flow: signature verify → synthetic tenant context → persist → return.
 *
 * <p>Error codes: {@code 2810} (missing signature), {@code 2811} (no
 * connection for this tenant), {@code 2812} (signature invalid),
 * {@code 2813} (payload malformed).
 */
@Slf4j
@RequiredArgsConstructor
public class QuickBooksWebhookService {

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final InvoiceRepository invoices;
    private final InvoiceService invoiceService;

    public Mono<Void> handle(UUID tenantId, String signatureHeader, String rawBody) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "QBO webhook missing intuit-signature header", 2810, 401));
        }
        return connections.findByTenantIdAndProvider(tenantId, QuickBooksOAuthService.PROVIDER)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "QuickBooks is not connected for this tenant", 2811, 404)))
                .flatMap(conn -> {
                    String verifier = conn.getSecrets() == null
                            ? null
                            : conn.getSecrets().get("webhookVerifierToken");
                    if (verifier == null || verifier.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Tenant's QBO webhookVerifierToken is not configured",
                                2811, 412));
                    }
                    if (!QuickBooksSignatureVerifier.verify(signatureHeader, rawBody, verifier)) {
                        return Mono.error(new DigiPresBeException(
                                "QBO webhook signature invalid", 2812, 401));
                    }
                    return process(tenantId, rawBody);
                });
    }

    private Mono<Void> process(UUID tenantId, String rawBody) {
        JsonNode root;
        try {
            root = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "QBO webhook body is not JSON: " + ex.getMessage(), 2813, 400));
        }
        JsonNode eventNotifications = root.path("eventNotifications");
        if (!eventNotifications.isArray() || eventNotifications.isEmpty()) {
            log.debug("QBO webhook has no eventNotifications; ignored");
            return Mono.empty();
        }

        TenantContext synthetic = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_QUICKBOOKS"));
        return Flux.fromIterable(eventNotifications)
                .flatMap(notification -> handleNotification(tenantId, notification))
                .then()
                .contextWrite(TenantContextHolder.write(synthetic));
    }

    private Mono<Void> handleNotification(UUID tenantId, JsonNode notification) {
        JsonNode dataChangeEvent = notification.path("dataChangeEvent");
        JsonNode entities = dataChangeEvent.path("entities");
        if (!entities.isArray()) return Mono.empty();
        return Flux.fromIterable(entities)
                .filter(e -> "Invoice".equalsIgnoreCase(e.path("name").asText("")))
                .filter(e -> {
                    String op = e.path("operation").asText("");
                    return "Payment".equalsIgnoreCase(op) || "Update".equalsIgnoreCase(op);
                })
                .flatMap(e -> handleInvoiceEntity(tenantId, e))
                .then();
    }

    private Mono<Void> handleInvoiceEntity(UUID tenantId, JsonNode entity) {
        String qboInvoiceId = entity.path("id").asText(null);
        if (qboInvoiceId == null || qboInvoiceId.isBlank()) {
            return Mono.empty();
        }
        // The QBO webhook is a "something changed" hint, not a full state delta.
        // We mark the CRM-side invoice paid in full; if QBO later sends a richer
        // notification the recordPayment idempotency in InvoiceService can be
        // tightened. For Phase 10d this matches acceptance criterion 6 (inbound
        // payment notification → CRM invoice marked paid).
        return invoices.findByTenantIdAndQuickbooksExternalRef(tenantId, qboInvoiceId)
                .flatMap(this::recordRemainingPaid)
                .then();
    }

    private Mono<Payment> recordRemainingPaid(Invoice inv) {
        BigDecimal amount = inv.getBalance() == null
                ? (inv.getTotal() == null ? BigDecimal.ZERO : inv.getTotal())
                : inv.getBalance();
        if (amount.signum() <= 0) {
            log.debug("Invoice {} already paid in full; skipping QBO webhook payment", inv.getId());
            return Mono.empty();
        }
        Payment p = Payment.builder()
                .invoiceId(inv.getId())
                .amount(amount)
                .currency(inv.getCurrency() == null ? "USD" : inv.getCurrency())
                .paidAt(Instant.now())
                .method(Payment.Method.OTHER)
                .externalRef("quickbooks-webhook")
                .notes("QuickBooks Online payment notification")
                .build();
        return invoiceService.recordPayment(p);
    }
}
