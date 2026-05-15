package com.kumouri.kmodigipresbe.integration.square;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.billing.Payment;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.PaymentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Syncs a Square POS payment to CRM records. Called from
 * {@link SquareWebhookService} when a {@code payment.completed} event arrives.
 *
 * <p>Flow:
 * <ol>
 *   <li>Upsert the buyer as a {@link Contact} (by email if present in the payment).</li>
 *   <li>Create a {@link Invoice} (status {@code PAID}) and a {@link Payment}
 *       (method {@code CARD}, externalRef = Square payment id).</li>
 *   <li>Publish {@link DomainEventType#INVOICE_PAID} so
 *       {@code LoyaltyAccrualService} triggers automatically.</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class SquarePosService {

    private final InvoiceRepository invoices;
    private final PaymentRepository payments;
    private final ContactRepository contacts;
    private final DomainEventPublisher events;

    /**
     * Persists the POS sale and publishes INVOICE_PAID.
     *
     * @param conn       the tenant's Square IntegrationConnection (for tenantId)
     * @param paymentId  Square payment id (idempotency anchor)
     * @param amountCents  total amount in smallest currency unit (cents for USD)
     * @param currency   ISO-4217 currency code
     * @param buyerEmail optional buyer email; null creates an anonymous invoice
     * @param description human-readable description for the line item
     */
    public Mono<Invoice> syncSale(IntegrationConnection conn, String paymentId,
                                  long amountCents, String currency,
                                  String buyerEmail, String description) {
        UUID tenantId = conn.getTenantId();
        BigDecimal amount = BigDecimal.valueOf(amountCents, 2).setScale(2, RoundingMode.HALF_UP);

        return resolveContact(tenantId, buyerEmail)
                .flatMap(contactId -> createInvoiceAndPayment(
                        tenantId, contactId, paymentId, amount, currency, description));
    }

    private Mono<UUID> resolveContact(UUID tenantId, String email) {
        if (email == null || email.isBlank()) {
            return Mono.just((UUID) null);
        }
        return contacts.findByTenantAndEmailAddress(tenantId, email)
                .next()
                .map(Contact::getId)
                .switchIfEmpty(Mono.defer(() ->
                        contacts.save(Contact.builder()
                                        .type(ContactType.PERSON)
                                        .displayName(email)
                                        .emails(List.of(new EmailContact(email)))
                                        .tags(java.util.Set.of("square-pos"))
                                        .build())
                                .map(Contact::getId)));
    }

    private Mono<Invoice> createInvoiceAndPayment(UUID tenantId, UUID contactId,
                                                   String squarePaymentId,
                                                   BigDecimal amount, String currency,
                                                   String description) {
        LineItem line = LineItem.builder()
                .description(description != null ? description : "Square POS sale")
                .quantity(BigDecimal.ONE)
                .unitPrice(amount)
                .lineTotal(amount)
                .build();

        Invoice invoice = Invoice.builder()
                .tenantId(tenantId)
                .contactId(contactId)
                .status(Invoice.Status.PAID)
                .currency(currency != null ? currency : "USD")
                .lineItems(List.of(line))
                .subtotal(amount)
                .total(amount)
                .balance(BigDecimal.ZERO)
                .issuedAt(LocalDate.now())
                .statusChangedAt(Instant.now())
                .externalRefs(Map.of("square", squarePaymentId))
                .build();

        return invoices.save(invoice)
                .flatMap(saved -> {
                    Payment payment = Payment.builder()
                            .tenantId(tenantId)
                            .invoiceId(saved.getId())
                            .amount(amount)
                            .currency(saved.getCurrency())
                            .paidAt(Instant.now())
                            .method(Payment.Method.CARD)
                            .externalRef(squarePaymentId)
                            .build();
                    return payments.save(payment).thenReturn(saved);
                })
                .doOnNext(saved -> emitInvoicePaid(saved));
    }

    private void emitInvoicePaid(Invoice invoice) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoice.getId());
        if (invoice.getContactId() != null) {
            payload.put("contactId", invoice.getContactId());
        }
        payload.put("total", invoice.getTotal());
        payload.put("source", "square-pos");
        events.publish(DomainEvent.of(
                DomainEventType.INVOICE_PAID,
                invoice.getTenantId(),
                invoice.getId(),
                payload));
    }
}
