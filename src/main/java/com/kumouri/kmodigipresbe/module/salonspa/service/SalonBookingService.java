package com.kumouri.kmodigipresbe.module.salonspa.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Booking CRUD + status transitions. Deposit invoices are created directly via
 * {@link InvoiceRepository} rather than {@link com.kumouri.kmodigipresbe.service.billing.InvoiceService}
 * to avoid pulling the full billing service (Stripe, QBO, events) into the module's
 * dependency graph — the module only needs to persist the invoice, not process it.
 *
 * <p>Status machine: {@code PENDING_DEPOSIT → CONFIRMED → COMPLETED}
 * and {@code CONFIRMED → CANCELLED} / {@code PENDING_DEPOSIT → CANCELLED}.
 */
@RequiredArgsConstructor
public class SalonBookingService {

    private final BookingRepository bookings;
    private final BookingPolicyService policy;
    private final InvoiceRepository invoiceRepo;
    private final DomainEventPublisher events;

    public Flux<Booking> findAll() {
        return bookings.findAll();
    }

    public Flux<Booking> findByContact(UUID contactId) {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> bookings.findByTenantIdAndContactId(ctx.tenantId(), contactId));
    }

    public Mono<Booking> findById(UUID id) {
        return bookings.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Booking not found", 2900, 404)));
    }

    public Mono<Booking> update(UUID id, Booking patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getStaffMemberId() != null) existing.setStaffMemberId(patch.getStaffMemberId());
            if (patch.getScheduledStart() != null) existing.setScheduledStart(patch.getScheduledStart());
            if (patch.getScheduledEnd() != null) existing.setScheduledEnd(patch.getScheduledEnd());
            if (patch.getNotes() != null) existing.setNotes(patch.getNotes());
            if (patch.getLoyaltyAccountId() != null) existing.setLoyaltyAccountId(patch.getLoyaltyAccountId());
            return bookings.save(existing);
        });
    }

    /**
     * Creates a booking after running policy validation. If the first service menu
     * item in the request requires a deposit, a DRAFT invoice is created and the
     * booking lands in {@code PENDING_DEPOSIT}; otherwise it lands in {@code CONFIRMED}.
     */
    public Mono<Booking> create(Booking toCreate) {
        toCreate.setId(null);
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID tenantId = ctx.tenantId();
            return policy.validate(tenantId,
                            toCreate.getServiceMenuItemId(),
                            toCreate.getStaffMemberId(),
                            toCreate.getScheduledStart(),
                            toCreate.getScheduledEnd())
                    .then(persistWithDeposit(toCreate));
        });
    }

    /**
     * Requires a deposit on an <em>already-created</em> upcoming booking — the additive entry point
     * the ChairFill (CF-2) {@code RiskTieredPreventionService} calls when a HIGH no-show-risk booking
     * needs a deposit it did not originally carry. Reuses the exact create-time deposit path
     * ({@link #createDepositInvoice}, a DRAFT {@link Invoice} via {@link InvoiceRepository}) rather
     * than reinventing it — the only difference is it runs after creation instead of inside
     * {@link #create}.
     *
     * <p>Idempotent + safe: a no-op (returns the booking unchanged) when the booking is already
     * terminal (COMPLETED / CANCELLED / NO_SHOW), when a deposit invoice already exists
     * ({@code depositInvoiceId != null}), or when {@code depositAmount} is null / non-positive — so a
     * re-fired {@code BOOKING_RISK_SCORED} or a booking that was already deposit-gated never mints a
     * second invoice or corrupts state. On a valid require it sets {@code depositRequired=true},
     * stamps {@code depositAmount}, flips the status back to {@code PENDING_DEPOSIT} (unless already
     * paid/terminal), mints the DRAFT deposit invoice, and stores its id. No domain event is emitted
     * (the Stripe {@code INVOICE_PAID} path drives confirmation, exactly as create-time).
     *
     * <p>Salon-spa core behaviour is otherwise untouched: existing callers of {@link #create} /
     * {@link #confirm} / {@link #complete} / {@link #cancel} see no change (this is a new method).
     */
    public Mono<Booking> requireDepositNow(UUID bookingId, BigDecimal depositAmount) {
        return findById(bookingId).flatMap(booking -> {
            BookingStatus status = booking.getStatus();
            if (status == BookingStatus.COMPLETED
                    || status == BookingStatus.CANCELLED
                    || status == BookingStatus.NO_SHOW) {
                return Mono.just(booking); // terminal — never re-gate
            }
            if (booking.getDepositInvoiceId() != null) {
                return Mono.just(booking); // already deposit-gated — idempotent no-op
            }
            if (depositAmount == null || depositAmount.compareTo(BigDecimal.ZERO) <= 0) {
                return Mono.just(booking); // nothing sensible to charge — leave untouched
            }
            booking.setDepositRequired(true);
            booking.setDepositAmount(depositAmount);
            if (!booking.isDepositPaid()) {
                booking.setStatus(BookingStatus.PENDING_DEPOSIT);
            }
            return bookings.save(booking)
                    .flatMap(saved -> createDepositInvoice(saved)
                            .flatMap(inv -> {
                                saved.setDepositInvoiceId(inv.getId());
                                return bookings.save(saved);
                            }));
        });
    }

    private Mono<Booking> persistWithDeposit(Booking booking) {
        if (!booking.isDepositRequired() || booking.getDepositAmount() == null
                || booking.getDepositAmount().compareTo(BigDecimal.ZERO) <= 0) {
            booking.setStatus(BookingStatus.CONFIRMED);
            return bookings.save(booking);
        }
        booking.setStatus(BookingStatus.PENDING_DEPOSIT);
        return bookings.save(booking)
                .flatMap(saved -> createDepositInvoice(saved)
                        .flatMap(inv -> {
                            saved.setDepositInvoiceId(inv.getId());
                            return bookings.save(saved);
                        }));
    }

    private Mono<Invoice> createDepositInvoice(Booking booking) {
        BigDecimal amount = booking.getDepositAmount();
        LineItem line = LineItem.builder()
                .description("Deposit — " + booking.getServiceMenuItemName())
                .quantity(BigDecimal.ONE)
                .unitPrice(amount)
                .lineTotal(amount)
                .build();
        Invoice invoice = Invoice.builder()
                .contactId(booking.getContactId())
                .status(Invoice.Status.DRAFT)
                .lineItems(List.of(line))
                .subtotal(amount)
                .total(amount)
                .balance(amount)
                .issuedAt(LocalDate.now())
                .statusChangedAt(Instant.now())
                .build();
        return invoiceRepo.save(invoice);
    }

    /**
     * Transitions a {@code PENDING_DEPOSIT} booking to {@code CONFIRMED}. Called by
     * the Stripe webhook (Phase 8) when a deposit payment is captured. No domain
     * event is emitted here — the Stripe {@code INVOICE_PAID} event downstream
     * drives the loyalty accrual in Phase 12c.
     */
    public Mono<Booking> confirm(UUID id) {
        return findById(id).flatMap(booking -> {
            if (booking.getStatus() == BookingStatus.CONFIRMED) return Mono.just(booking);
            if (booking.getStatus() != BookingStatus.PENDING_DEPOSIT) {
                return Mono.error(new DigiPresBeException(
                        "Cannot confirm booking in status " + booking.getStatus(), 2900, 409));
            }
            booking.setStatus(BookingStatus.CONFIRMED);
            booking.setDepositPaid(true);
            return bookings.save(booking);
        });
    }

    /**
     * Marks a booking as completed and emits {@link DomainEventType#BOOKING_COMPLETED}
     * so the Phase 12c loyalty accrual and rebooking-nudge sequence can subscribe.
     */
    public Mono<Booking> complete(UUID id) {
        return findById(id).flatMap(booking -> {
            if (booking.getStatus() == BookingStatus.COMPLETED) return Mono.just(booking);
            if (booking.getStatus() != BookingStatus.CONFIRMED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot complete booking in status " + booking.getStatus(), 2900, 409));
            }
            booking.setStatus(BookingStatus.COMPLETED);
            return bookings.save(booking)
                    .doOnNext(saved -> emitCompleted(saved));
        });
    }

    public Mono<Booking> cancel(UUID id) {
        return findById(id).flatMap(booking -> {
            if (booking.getStatus() == BookingStatus.CANCELLED) return Mono.just(booking);
            if (booking.getStatus() == BookingStatus.COMPLETED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot cancel a completed booking", 2902, 409));
            }
            booking.setStatus(BookingStatus.CANCELLED);
            return bookings.save(booking);
        });
    }

    private void emitCompleted(Booking booking) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", booking.getId());
        payload.put("contactId", booking.getContactId());
        payload.put("staffMemberId", booking.getStaffMemberId());
        payload.put("loyaltyAccountId", booking.getLoyaltyAccountId());
        payload.put("serviceMenuItemId", booking.getServiceMenuItemId());
        events.publish(DomainEvent.of(
                DomainEventType.BOOKING_COMPLETED,
                booking.getTenantId(),
                booking.getId(),
                payload));
    }
}
