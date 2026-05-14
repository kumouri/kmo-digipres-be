package com.kumouri.kmodigipresbe.integration.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Receives Stripe webhook events scoped to a single tenant. Signature is
 * verified against the tenant's stored {@code webhookSigningSecret} before any
 * effect is applied — Stripe will retry with backoff if the receiver returns
 * 4xx/5xx, so a missing signature returns 401 and Stripe re-delivers later.
 *
 * <p>Handled events for Phase 8:
 * <ul>
 *   <li>{@code payment_intent.succeeded} — when the intent's metadata carries
 *       {@code kmosf_invoice_id}, records a {@link Payment} of the captured
 *       amount on that invoice and lets {@code InvoiceService.refreshInvoiceStatus}
 *       advance the invoice's status.</li>
 * </ul>
 *
 * <p>Other event types are acknowledged (200) and silently dropped.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    public static final String PROVIDER = "stripe";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final InvoiceService invoices;

    public Mono<Void> handle(UUID tenantId, String signatureHeader, String rawBody) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Stripe is not connected for this tenant", 2510, 404)))
                .flatMap(conn -> {
                    String secret = conn.getSecrets() == null
                            ? null
                            : conn.getSecrets().get("webhookSigningSecret");
                    if (secret == null || secret.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Tenant's Stripe webhookSigningSecret is not configured",
                                2511, 412));
                    }
                    if (!StripeSignatureVerifier.verify(signatureHeader, rawBody, secret)) {
                        return Mono.error(new DigiPresBeException(
                                "Stripe webhook signature invalid", 2512, 401));
                    }
                    return process(tenantId, rawBody);
                });
    }

    private Mono<Void> process(UUID tenantId, String rawBody) {
        JsonNode event;
        try {
            event = objectMapper.readTree(rawBody);
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "Stripe webhook body is not JSON: " + ex.getMessage(), 2513, 400));
        }
        String type = event.path("type").asText("");
        if ("payment_intent.succeeded".equals(type)) {
            return handlePaymentIntentSucceeded(tenantId, event);
        }
        log.debug("Stripe event {} ignored", type);
        return Mono.empty();
    }

    private Mono<Void> handlePaymentIntentSucceeded(UUID tenantId, JsonNode event) {
        JsonNode obj = event.path("data").path("object");
        JsonNode metadata = obj.path("metadata");
        String invoiceIdRaw = metadata.path("kmosf_invoice_id").asText(null);
        if (invoiceIdRaw == null || invoiceIdRaw.isBlank()) {
            log.debug("payment_intent.succeeded without kmosf_invoice_id metadata — ignored");
            return Mono.empty();
        }
        UUID invoiceId;
        try {
            invoiceId = UUID.fromString(invoiceIdRaw);
        } catch (IllegalArgumentException ex) {
            log.warn("kmosf_invoice_id '{}' is not a UUID", invoiceIdRaw);
            return Mono.empty();
        }
        long amountReceivedMinor = obj.path("amount_received").asLong(0L);
        if (amountReceivedMinor <= 0) {
            log.debug("amount_received is zero — ignored");
            return Mono.empty();
        }
        BigDecimal amount = BigDecimal.valueOf(amountReceivedMinor)
                .movePointLeft(2);  // Stripe amounts are in minor units
        String currency = obj.path("currency").asText("usd").toUpperCase();
        String paymentIntentId = obj.path("id").asText(null);

        Payment p = Payment.builder()
                .invoiceId(invoiceId)
                .amount(amount)
                .currency(currency)
                .paidAt(Instant.now())
                .method(Payment.Method.CARD)
                .externalRef(paymentIntentId)
                .notes("Stripe payment_intent.succeeded")
                .build();

        TenantContext webhookCtx = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_STRIPE"));
        return invoices.recordPayment(p)
                .then()
                .contextWrite(TenantContextHolder.write(webhookCtx));
    }
}
