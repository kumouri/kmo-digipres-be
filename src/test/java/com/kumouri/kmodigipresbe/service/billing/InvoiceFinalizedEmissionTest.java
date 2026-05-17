package com.kumouri.kmodigipresbe.service.billing;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.PaymentRepository;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 10d — verifies that finalizing an Invoice (DRAFT → SENT) publishes
 * an {@link DomainEventType#INVOICE_FINALIZED} event on the shared
 * {@link DomainEventPublisher}. Subscribers (QuickBooksInvoiceSync, future
 * workflow rules) key off this emission.
 */
class InvoiceFinalizedEmissionTest {

    private InvoiceRepository invoices;
    private PaymentRepository payments;
    private QuoteRepository quotes;
    private DomainEventPublisher events;
    private InvoiceNumberGenerator invoiceNumberGenerator;
    private InvoiceService service;
    private List<DomainEvent> observed;
    private Disposable subscription;

    @BeforeEach
    void setup() {
        invoices = mock(InvoiceRepository.class);
        payments = mock(PaymentRepository.class);
        quotes = mock(QuoteRepository.class);
        events = new DomainEventPublisher();
        // Phase E blocker resolution: setStatus now assigns a number at the
        // DRAFT→issued edge via InvoiceNumberGenerator. Stub it so these
        // event-emission unit tests still construct + run; the returned number
        // does not affect the INVOICE_FINALIZED assertions.
        invoiceNumberGenerator = mock(InvoiceNumberGenerator.class);
        when(invoiceNumberGenerator.next(any(UUID.class)))
                .thenReturn(Mono.just("INV-2026-0001"));
        service = new InvoiceService(invoices, payments, quotes, events,
                invoiceNumberGenerator);
        observed = new CopyOnWriteArrayList<>();
        subscription = events.stream().subscribe(observed::add);
    }

    @Test
    void setStatus_draftToSent_emitsInvoiceFinalized() {
        UUID tenantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        Invoice draft = Invoice.builder()
                .id(invoiceId)
                .tenantId(tenantId)
                .status(Invoice.Status.DRAFT)
                .currency("USD")
                .contactId(contactId)
                .total(new BigDecimal("250.00"))
                .build();
        when(invoices.findById(eq(invoiceId))).thenReturn(Mono.just(draft));
        when(payments.findAllByTenantIdAndInvoiceId(eq(tenantId), eq(invoiceId)))
                .thenReturn(Flux.empty());
        when(invoices.save(any(Invoice.class)))
                .thenAnswer(inv -> Mono.just((Invoice) inv.getArgument(0)));

        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        service.setStatus(invoiceId, Invoice.Status.SENT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        Awaitility.await().atMost(2, TimeUnit.SECONDS)
                .until(() -> observed.stream()
                        .anyMatch(e -> DomainEventType.INVOICE_FINALIZED.equals(e.type())));

        DomainEvent evt = observed.stream()
                .filter(e -> DomainEventType.INVOICE_FINALIZED.equals(e.type()))
                .findFirst()
                .orElseThrow();
        assertThat(evt.tenantId()).isEqualTo(tenantId);
        assertThat(evt.subjectId()).isEqualTo(invoiceId);
        assertThat(evt.payload()).containsEntry("invoiceId", invoiceId.toString());
        assertThat(evt.payload()).containsEntry("contactId", contactId.toString());
        assertThat(evt.payload()).containsEntry("currency", "USD");
        assertThat(evt.payload()).containsEntry("totalAmount", new BigDecimal("250.00"));

        subscription.dispose();
    }

    @Test
    void setStatus_sentToSent_doesNotEmit() {
        UUID tenantId = UUID.randomUUID();
        UUID invoiceId = UUID.randomUUID();
        Invoice sent = Invoice.builder()
                .id(invoiceId)
                .tenantId(tenantId)
                .status(Invoice.Status.SENT)
                .currency("USD")
                .total(new BigDecimal("100.00"))
                .build();
        when(invoices.findById(eq(invoiceId))).thenReturn(Mono.just(sent));
        when(payments.findAllByTenantIdAndInvoiceId(eq(tenantId), eq(invoiceId)))
                .thenReturn(Flux.empty());
        when(invoices.save(any(Invoice.class)))
                .thenAnswer(inv -> Mono.just((Invoice) inv.getArgument(0)));

        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        service.setStatus(invoiceId, Invoice.Status.SENT)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // Give the multicast sink a tick to deliver, then assert nothing showed up.
        try {
            Thread.sleep(200);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertThat(observed).noneMatch(e -> DomainEventType.INVOICE_FINALIZED.equals(e.type()));

        subscription.dispose();
    }
}
