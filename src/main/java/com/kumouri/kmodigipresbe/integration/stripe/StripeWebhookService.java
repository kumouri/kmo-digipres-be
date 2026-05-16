package com.kumouri.kmodigipresbe.integration.stripe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.model.billing.StripeWebhookEvent;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.StripeWebhookEventRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Receives Stripe webhook events scoped to a single tenant. Signature is
 * verified against the tenant's stored {@code webhookSigningSecret} before any
 * effect is applied — Stripe will retry with backoff if the receiver returns
 * 4xx/5xx, so a missing signature returns 401 and Stripe re-delivers later.
 *
 * <h2>Phase E (E-D7) — idempotent on the Stripe EVENT id + emits INVOICE_PAID</h2>
 * Two pre-existing money gaps are closed here WITHOUT touching the verified
 * signature path or {@code InvoiceService.recordPayment}:
 * <ol>
 *   <li><strong>Idempotent on the Stripe event id.</strong> Today's service
 *       double-records a Payment when Stripe re-delivers an event. Now the flow is
 *       an <strong>explicit-boolean probe</strong> on {@code event.id}
 *       ({@code .map(e -> true).defaultIfEmpty(false).flatMap(seen -> seen ?
 *       Mono.empty() : processAndRecord(...))}) — NEVER
 *       {@code switchIfEmpty(process)}. A duplicate is acknowledged
 *       <strong>200 no-op</strong> (a 409 makes Stripe retry harder). The dedupe
 *       key is the <strong>event</strong> id, NOT the payment-intent id (one
 *       intent → many events). {@code processAndRecord} inserts the
 *       {@link StripeWebhookEvent} ledger row <strong>FIRST</strong> (unique
 *       {@code tenant_event_idx}) so a concurrent re-delivery's second insert hits
 *       {@code DuplicateKeyException} and records ZERO second Payment.</li>
 *   <li><strong>Emits {@code INVOICE_PAID}.</strong> Mirrors
 *       {@code SquarePosService.emitInvoicePaid} so {@code LoyaltyAccrualService}
 *       (which already keys off {@code INVOICE_PAID}) accrues on Stripe payments.</li>
 * </ol>
 * The shipped {@link StripeSignatureVerifier} is reused <strong>verbatim</strong>
 * (zero new HMAC code); codes 2510-2513/2520 unchanged; {@code 3610} is a new
 * defensive code for an event missing its {@code id}.
 *
 * <p>No live Stripe anywhere — signatures are verified with a test signing secret
 * in tests (§7 hard boundary).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StripeWebhookService {

    public static final String PROVIDER = "stripe";

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final InvoiceService invoices;
    private final InvoiceRepository invoiceRepository;
    private final StripeWebhookEventRepository stripeEvents;
    private final DomainEventPublisher events;

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
        String eventId = event.path("id").asText(null);
        if (eventId == null || eventId.isBlank()) {
            // Defensive: a well-formed Stripe event always carries an id. Without
            // it we cannot dedupe — reject (400) rather than risk double-crediting.
            return Mono.error(new DigiPresBeException(
                    "Stripe webhook event is missing its id", 3610, 400));
        }
        String type = event.path("type").asText("");

        // Explicit-boolean idempotency probe on the EVENT id (E-D7 / §9 item 4).
        // NOT switchIfEmpty(processAndRecord) — that fires whenever the probe
        // completes empty and would re-process on a cache HIT.
        return stripeEvents.findByTenantIdAndStripeEventId(tenantId, eventId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (Boolean.TRUE.equals(seen)) {
                        // Duplicate delivery — acknowledge 200 no-op (NOT 409;
                        // Stripe retries harder on non-2xx).
                        log.debug("Stripe event {} already processed for tenant {} "
                                + "— 200 no-op", eventId, tenantId);
                        return Mono.empty();
                    }
                    return processAndRecord(tenantId, eventId, type, event);
                });
    }

    /**
     * Ledger-insert FIRST (E-D7), then the existing recordPayment, then INVOICE_PAID.
     * For non-payment events the ledger row is still written (so a re-delivery of an
     * ignored event is also a 200 no-op) but no Payment / event is produced.
     */
    private Mono<Void> processAndRecord(UUID tenantId, String eventId, String type, JsonNode event) {
        StripeWebhookEvent ledger = StripeWebhookEvent.builder()
                .tenantId(tenantId)
                .stripeEventId(eventId)
                .eventType(type)
                .receivedAt(Instant.now())
                .build();

        Mono<Void> body = stripeEvents.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    // Concurrent re-delivery raced us to the unique index — the
                    // other delivery owns this event. 200 no-op, ZERO second Payment.
                    log.debug("Stripe event {} concurrently processed for tenant {} "
                            + "— 200 no-op", eventId, tenantId);
                    return Mono.empty();
                })
                .flatMap(savedLedger -> {
                    if (!"payment_intent.succeeded".equals(type)) {
                        log.debug("Stripe event {} ignored", type);
                        return Mono.empty();
                    }
                    return handlePaymentIntentSucceeded(tenantId, event, savedLedger);
                });

        TenantContext webhookCtx = new TenantContext(
                tenantId, null, Set.of("INTEGRATION_STRIPE"));
        return body.contextWrite(TenantContextHolder.write(webhookCtx));
    }

    private Mono<Void> handlePaymentIntentSucceeded(UUID tenantId, JsonNode event,
                                                    StripeWebhookEvent savedLedger) {
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

        // recordPayment is UNCHANGED — it auto-advances PAID via refreshInvoiceStatus.
        return invoices.recordPayment(p)
                .flatMap(savedPayment -> {
                    // Back-fill the ledger with the correlation (audit/trace).
                    savedLedger.setInvoiceId(invoiceId);
                    savedLedger.setPaymentId(savedPayment.getId());
                    return stripeEvents.save(savedLedger)
                            .then(emitInvoicePaid(tenantId, invoiceId, amount, currency));
                });
    }

    /**
     * Mirrors {@code SquarePosService.emitInvoicePaid:130-143} so the existing
     * {@code LoyaltyAccrualService} (keyed on {@code INVOICE_PAID}) reacts to
     * Stripe payments too. {@code source:"stripe"} distinguishes the origin.
     *
     * <p>{@code contactId} is enriched best-effort from the invoice
     * ({@code map(...).defaultIfEmpty(null)} — deliberately NOT {@code switchIfEmpty},
     * which the §9-item-5 grep flags; the Payment is already recorded so this is a
     * pure advisory enrichment, never a conditional pay).
     */
    private Mono<Void> emitInvoicePaid(UUID tenantId, UUID invoiceId,
                                       BigDecimal amount, String currency) {
        return invoiceRepository.findById(invoiceId)
                .map(inv -> inv.getContactId() == null
                        ? "" : inv.getContactId().toString())
                .defaultIfEmpty("")
                .doOnNext(contactId -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("invoiceId", invoiceId.toString());
                    if (!contactId.isEmpty()) {
                        payload.put("contactId", contactId);
                    }
                    payload.put("amount", amount);
                    payload.put("currency", currency);
                    payload.put("source", "stripe");
                    events.publish(DomainEvent.of(
                            DomainEventType.INVOICE_PAID, tenantId, invoiceId, payload));
                })
                .then();
    }
}
