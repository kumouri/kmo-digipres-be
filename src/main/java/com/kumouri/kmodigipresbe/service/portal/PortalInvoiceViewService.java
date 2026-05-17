package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Best-effort advisory side-effects for the portal invoice-view event (Phase G — G-D4).
 *
 * <p>Emits {@link DomainEventType#INVOICE_VIEWED_BY_CLIENT} and creates an
 * {@link Activity Activity(type=NOTE)} row via {@link ActivityCrudService} so the view
 * appears on the contact timeline. Both operations are <strong>best-effort</strong>:
 * any failure is swallowed with a WARN log so that a telemetry write can NEVER fail a
 * paying client's invoice read (G-D4 mandatory invariant).
 *
 * <p>Lifecycle note: this is a <strong>stateless</strong> {@code @Service} — it holds no
 * SDK client, no HTTP/Netty client, no pool, no scheduler, and no thread pool. Verified:
 * no new resource-owning bean is introduced by this class (the Phase-F F.2/F.11
 * lifecycle checklist — mandatory regardless; answered "none" explicitly here).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PortalInvoiceViewService {

    private final DomainEventPublisher events;
    private final ActivityCrudService activityCrud;

    /**
     * Fire-and-forget advisory emission for a portal invoice view. Returns
     * {@code Mono<Void>} that completes regardless of whether the activity write or
     * event publish succeeds. Errors are logged at WARN and suppressed — the caller
     * must not subscribe to this and propagate errors to the response.
     *
     * <p>Usage: {@code .then(viewService.recordView(invoice, contact).onErrorResume(...))}
     * — caller wraps further in {@code onErrorResume(e -> Mono.empty())} for safety in
     * case this service itself throws synchronously, but the internal chain already
     * handles async failures.
     */
    public Mono<Void> recordView(Invoice invoice, Contact contact) {
        return TenantContextHolder.current()
                .flatMap(ctx -> {
                    UUID viewedByUserId = ctx.userId();
                    // Advisory domain event — fire-and-forget, never fails the read.
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("invoiceId", invoice.getId() == null ? null : invoice.getId().toString());
                    payload.put("contactId", contact.getId() == null ? null : contact.getId().toString());
                    payload.put("viewedByUserId", viewedByUserId == null ? null : viewedByUserId.toString());
                    try {
                        events.publish(DomainEvent.of(
                                DomainEventType.INVOICE_VIEWED_BY_CLIENT,
                                contact.getTenantId(),
                                invoice.getId(),
                                payload));
                    } catch (Exception e) {
                        log.warn("Failed to publish INVOICE_VIEWED_BY_CLIENT event for invoice {}: {}",
                                invoice.getId(), e.getMessage());
                    }

                    // Activity(NOTE) row — best-effort; never fails the read.
                    String invoiceRef = invoice.getInvoiceNumber() != null
                            ? invoice.getInvoiceNumber()
                            : (invoice.getId() != null ? invoice.getId().toString() : "unknown");
                    Activity viewActivity = Activity.builder()
                            .type(ActivityType.NOTE)
                            .direction(ActivityDirection.INBOUND)
                            .subjectType(SubjectType.CONTACT)
                            .subjectId(contact.getId())
                            .summary("Invoice " + invoiceRef + " viewed by client")
                            .payload(payload)
                            .build();
                    return activityCrud.create(viewActivity)
                            .then()
                            .onErrorResume(e -> {
                                log.warn("Failed to create INVOICE_VIEWED_BY_CLIENT Activity for invoice {}: {}",
                                        invoice.getId(), e.getMessage());
                                return Mono.empty();
                            });
                })
                // If there's no tenant context (should not happen in normal portal flow) — best-effort.
                .onErrorResume(e -> {
                    log.warn("Failed to record invoice view (no tenant context or upstream error): {}", e.getMessage());
                    return Mono.empty();
                });
    }
}
