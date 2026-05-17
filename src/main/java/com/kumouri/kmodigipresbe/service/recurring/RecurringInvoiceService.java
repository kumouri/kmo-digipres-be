package com.kumouri.kmodigipresbe.service.recurring;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice.Status;
import com.kumouri.kmodigipresbe.repository.recurring.RecurringInvoiceRepository;
import com.kumouri.kmodigipresbe.service.scheduling.RecurringSchedule;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Manages the {@link RecurringInvoice} lifecycle (Phase E — E-D2, E-D12).
 *
 * <h2>RRULE validation</h2>
 * {@code rrule} is validated through the existing {@link RecurringSchedule}
 * ({@code Rfc5545RecurringSchedule}); a malformed rule throws the existing
 * {@code DigiPresBeException(1300, 400)} — reused, NOT re-allocated (E-D11).
 * The initial {@link RecurringInvoice#getNextRunAt()} cursor is the first
 * occurrence at/after {@code seedAt} (a row whose first occurrence is in the past
 * is immediately due — the spawn service's bounded catch-up handles it; that is
 * correct, not a bug).
 *
 * <h2>§9 invariant — explicit boolean idempotency, NEVER switchIfEmpty(create)</h2>
 * {@code switchIfEmpty} is used here ONLY for genuine not-found (3605). No
 * conditional create/spawn is gated by {@code switchIfEmpty}.
 */
@Service
@RequiredArgsConstructor
public class RecurringInvoiceService {

    /**
     * Illegal {@link Status} transitions (the {@code MilestoneService.ILLEGAL_TRANSITIONS}
     * / {@code ExpenseService} pattern). ENDED is terminal; PAUSED⇄ACTIVE is allowed;
     * a no-op self-transition is rejected as defensive (3606).
     */
    private static final Set<String> ILLEGAL_TRANSITIONS = Set.of(
            "ENDED->ACTIVE",
            "ENDED->PAUSED",
            "ENDED->ENDED",
            "ACTIVE->ACTIVE",
            "PAUSED->PAUSED"
    );

    private final RecurringInvoiceRepository recurringInvoices;
    private final RecurringSchedule recurringSchedule;
    private final DomainEventPublisher events;

    // -------------------------------------------------------------------------
    // Query
    // -------------------------------------------------------------------------

    public Flux<RecurringInvoice> findAll() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> recurringInvoices.findAllByTenantId(ctx.tenantId()));
    }

    public Mono<RecurringInvoice> findById(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> recurringInvoices.findByTenantIdAndId(ctx.tenantId(), id))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "RecurringInvoice not found", 3605, 404)));
    }

    // -------------------------------------------------------------------------
    // Create
    // -------------------------------------------------------------------------

    public Mono<RecurringInvoice> create(RecurringInvoice body) {
        return TenantContextHolder.required().flatMap(ctx -> {
            if (body.getTemplateName() == null || body.getTemplateName().isBlank()) {
                return Mono.error(new DigiPresBeException("templateName is required", 3601, 400));
            }
            if (body.getLineItems() == null || body.getLineItems().isEmpty()) {
                return Mono.error(new DigiPresBeException("lineItems must be non-empty", 3602, 400));
            }
            if (body.getRrule() == null || body.getRrule().isBlank()) {
                return Mono.error(new DigiPresBeException("rrule is required", 3603, 400));
            }
            if (body.getSeedAt() == null) {
                return Mono.error(new DigiPresBeException("seedAt is required", 3604, 400));
            }
            // Validate the RRULE eagerly + compute the initial nextRunAt cursor.
            // Rfc5545RecurringSchedule.expand throws DigiPresBeException(1300,400) on a
            // malformed rule; wrap in fromCallable so it flows through the Mono pipeline.
            return Mono.fromCallable(() ->
                            recurringSchedule.next(body.getRrule(), body.getSeedAt(), body.getSeedAt())
                                    .orElse(null))
                    .flatMap(initialNextRun -> {
                        body.setId(null);
                        body.setTenantId(ctx.tenantId());
                        if (body.getStatus() == null) {
                            body.setStatus(Status.ACTIVE);
                        }
                        if (body.getCurrency() == null || body.getCurrency().isBlank()) {
                            body.setCurrency("USD");
                        }
                        if (body.getPaymentTerms() == null) {
                            body.setPaymentTerms(com.kumouri.kmodigipresbe.model.billing.Invoice
                                    .PaymentTerms.NET_30);
                        }
                        body.setLastRunAt(null);
                        body.setLastSpawnedInvoiceId(null);
                        body.setOccurrenceCount(0);
                        // No occurrence ever (e.g. COUNT=0, or seed past a UNTIL) ⇒ ENDED.
                        if (initialNextRun == null) {
                            body.setNextRunAt(null);
                            body.setStatus(Status.ENDED);
                        } else {
                            body.setNextRunAt(initialNextRun);
                        }
                        return recurringInvoices.save(body)
                                .flatMap(saved -> {
                                    publish(DomainEventType.RECURRING_INVOICE_CREATED, saved,
                                            Map.of("templateName", saved.getTemplateName()));
                                    if (saved.getStatus() == Status.ENDED) {
                                        publish(DomainEventType.RECURRING_INVOICE_ENDED, saved,
                                                Map.of("reason", "no future occurrence at creation"));
                                    }
                                    return Mono.just(saved);
                                });
                    });
        });
    }

    // -------------------------------------------------------------------------
    // Update
    // -------------------------------------------------------------------------

    /**
     * Updates a recurring invoice. An {@code ENDED} template is immutable (3607).
     * Changing {@code rrule} or {@code seedAt} re-validates the RRULE (→ 1300) and
     * recomputes {@link RecurringInvoice#getNextRunAt()} from the new
     * rrule/seed/lastRunAt cursor so the next tick is correct.
     */
    public Mono<RecurringInvoice> update(UUID id, RecurringInvoice patch) {
        return findById(id).flatMap(existing -> {
            if (existing.getStatus() == Status.ENDED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot modify an ENDED RecurringInvoice", 3607, 409));
            }
            if (patch.getTemplateName() != null) {
                if (patch.getTemplateName().isBlank()) {
                    return Mono.error(new DigiPresBeException("templateName is required", 3601, 400));
                }
                existing.setTemplateName(patch.getTemplateName());
            }
            if (patch.getLineItems() != null) {
                if (patch.getLineItems().isEmpty()) {
                    return Mono.error(new DigiPresBeException("lineItems must be non-empty", 3602, 400));
                }
                existing.setLineItems(patch.getLineItems());
            }
            if (patch.getContactId() != null) existing.setContactId(patch.getContactId());
            if (patch.getCompanyId() != null) existing.setCompanyId(patch.getCompanyId());
            if (patch.getDealId() != null)    existing.setDealId(patch.getDealId());
            if (patch.getProjectId() != null) existing.setProjectId(patch.getProjectId());
            if (patch.getCurrency() != null && !patch.getCurrency().isBlank()) {
                existing.setCurrency(patch.getCurrency());
            }
            if (patch.getPaymentTerms() != null) existing.setPaymentTerms(patch.getPaymentTerms());
            if (patch.getEndAt() != null) existing.setEndAt(patch.getEndAt());
            existing.setAutoFinalize(patch.isAutoFinalize());

            boolean ruleChanged = patch.getRrule() != null && !patch.getRrule().isBlank()
                    && !patch.getRrule().equals(existing.getRrule());
            boolean seedChanged = patch.getSeedAt() != null
                    && !patch.getSeedAt().equals(existing.getSeedAt());
            if (patch.getRrule() != null && patch.getRrule().isBlank()) {
                return Mono.error(new DigiPresBeException("rrule is required", 3603, 400));
            }
            if (ruleChanged) existing.setRrule(patch.getRrule());
            if (seedChanged) existing.setSeedAt(patch.getSeedAt());

            if (ruleChanged || seedChanged) {
                // Recompute the cursor from the new rrule/seed. If a period was
                // already spawned, start strictly AFTER it (+1s clears ical4j's
                // second-granularity inclusive `from`); otherwise from the seed.
                java.time.Instant cursorFrom = existing.getLastRunAt() != null
                        ? existing.getLastRunAt().plusSeconds(1)
                        : existing.getSeedAt();
                return Mono.fromCallable(() ->
                                recurringSchedule.next(existing.getRrule(), existing.getSeedAt(),
                                        cursorFrom).orElse(null))
                        .flatMap(nextRun -> {
                            existing.setNextRunAt(nextRun);
                            return recurringInvoices.save(existing);
                        });
            }
            return recurringInvoices.save(existing);
        });
    }

    // -------------------------------------------------------------------------
    // Status transition
    // -------------------------------------------------------------------------

    public Mono<RecurringInvoice> setStatus(UUID id, Status target) {
        return findById(id).flatMap(existing -> {
            String transitionKey = existing.getStatus().name() + "->" + target.name();
            if (ILLEGAL_TRANSITIONS.contains(transitionKey)) {
                return Mono.error(new DigiPresBeException(
                        "Transition " + transitionKey + " is not permitted", 3606, 409));
            }
            Status previous = existing.getStatus();
            existing.setStatus(target);
            return recurringInvoices.save(existing)
                    .flatMap(saved -> {
                        if (target == Status.PAUSED) {
                            publish(DomainEventType.RECURRING_INVOICE_PAUSED, saved,
                                    Map.of("previous", previous.name()));
                        } else if (target == Status.ENDED) {
                            publish(DomainEventType.RECURRING_INVOICE_ENDED, saved,
                                    Map.of("previous", previous.name(), "reason", "manual"));
                        }
                        return Mono.just(saved);
                    });
        });
    }

    // -------------------------------------------------------------------------
    // Delete
    // -------------------------------------------------------------------------

    public Mono<Void> delete(UUID id) {
        return findById(id).flatMap(ri -> recurringInvoices.deleteById(ri.getId()));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void publish(String type, RecurringInvoice ri, Map<String, Object> extra) {
        Map<String, Object> payload = new HashMap<>(extra);
        payload.put("recurringInvoiceId", ri.getId().toString());
        events.publish(DomainEvent.of(type, ri.getTenantId(), ri.getId(), payload));
    }
}
