package com.kumouri.kmodigipresbe.module.restaurantlight.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.CateringOrder;
import com.kumouri.kmodigipresbe.module.restaurantlight.model.CateringOrderStatus;
import com.kumouri.kmodigipresbe.module.restaurantlight.repository.CateringOrderRepository;
import com.kumouri.kmodigipresbe.service.quote.QuoteService;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Business logic for catering orders. Composes {@link QuoteService} (Phase 7)
 * for the quote-issuance step: creates a DRAFT {@link Quote} linked to the
 * order's contact and annotated with event details, then transitions the order
 * to {@link CateringOrderStatus#QUOTE_SENT}. Staff add line items directly on
 * the Quote; Quote acceptance (ACCEPTED) and Invoice creation happen in the
 * Phase-7 billing flow and are not driven from here.
 */
@RequiredArgsConstructor
public class CateringOrderService {

    private final CateringOrderRepository orders;
    private final QuoteService quotes;
    private final DomainEventPublisher events;

    public Flux<CateringOrder> findAll() {
        return orders.findAll();
    }

    public Mono<CateringOrder> findById(UUID id) {
        return orders.findById(id)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "CateringOrder not found", 1411, 404)));
    }

    public Mono<CateringOrder> create(CateringOrder toCreate) {
        toCreate.setId(null);
        if (toCreate.getStatus() == null) toCreate.setStatus(CateringOrderStatus.INQUIRY);
        return orders.save(toCreate);
    }

    public Mono<CateringOrder> update(UUID id, CateringOrder patch) {
        return findById(id).flatMap(existing -> {
            if (patch.getContactId() != null) existing.setContactId(patch.getContactId());
            if (patch.getEventDate() != null) existing.setEventDate(patch.getEventDate());
            if (patch.getEventTime() != null) existing.setEventTime(patch.getEventTime());
            if (patch.getHeadcount() > 0) existing.setHeadcount(patch.getHeadcount());
            if (patch.getDeliveryMode() != null) existing.setDeliveryMode(patch.getDeliveryMode());
            if (patch.getDietaryRequirements() != null) existing.setDietaryRequirements(patch.getDietaryRequirements());
            if (patch.getSpecialRequests() != null) existing.setSpecialRequests(patch.getSpecialRequests());
            if (patch.getCustomFields() != null) existing.setCustomFields(patch.getCustomFields());
            return orders.save(existing);
        });
    }

    public Mono<Void> delete(UUID id) {
        return orders.deleteById(id);
    }

    /**
     * Creates a DRAFT Phase-7 {@link Quote} for the given catering order and
     * transitions the order to {@link CateringOrderStatus#QUOTE_SENT}.
     *
     * <p>The quote is intentionally created with no line items — staff populate
     * pricing in the Phase-7 quote editor. The order's event details are captured
     * in the quote notes so context is preserved without duplication.
     *
     * <p>Publishes {@code CATERING_ORDER_QUOTE_ISSUED} for downstream WorkflowRule
     * automations (e.g. confirmation email sequence seeded at tenant onboarding).
     *
     * @throws DigiPresBeException errorCode 1412 if the order is already CONFIRMED,
     *     COMPLETED, or CANCELLED.
     */
    public Mono<CateringOrder> issueQuote(UUID id) {
        return findById(id).flatMap(order -> {
            if (order.getStatus() == CateringOrderStatus.CONFIRMED
                    || order.getStatus() == CateringOrderStatus.COMPLETED
                    || order.getStatus() == CateringOrderStatus.CANCELLED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot issue quote for a " + order.getStatus() + " catering order",
                        1412, 409));
            }
            Quote draft = Quote.builder()
                    .contactId(order.getContactId())
                    .notes(buildQuoteNotes(order))
                    .lineItems(List.of())
                    .issuedAt(LocalDate.now())
                    .build();
            return quotes.create(draft).flatMap(savedQuote -> {
                order.setQuoteId(savedQuote.getId());
                order.setStatus(CateringOrderStatus.QUOTE_SENT);
                return orders.save(order).doOnSuccess(saved ->
                        events.publish(DomainEvent.of(
                                DomainEventType.CATERING_ORDER_QUOTE_ISSUED,
                                saved.getTenantId(),
                                saved.getId(),
                                Map.of("quoteId", savedQuote.getId(),
                                        "contactId", String.valueOf(saved.getContactId()),
                                        "headcount", saved.getHeadcount()))));
            });
        });
    }

    /**
     * Transitions the order to {@link CateringOrderStatus#CONFIRMED}. Publishes
     * {@code CATERING_ORDER_CONFIRMED} for downstream automations.
     *
     * @throws DigiPresBeException errorCode 1412 if the order is COMPLETED or CANCELLED.
     */
    public Mono<CateringOrder> confirm(UUID id) {
        return findById(id).flatMap(order -> {
            if (order.getStatus() == CateringOrderStatus.COMPLETED
                    || order.getStatus() == CateringOrderStatus.CANCELLED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot confirm a " + order.getStatus() + " catering order",
                        1412, 409));
            }
            order.setStatus(CateringOrderStatus.CONFIRMED);
            return orders.save(order).doOnSuccess(saved ->
                    events.publish(DomainEvent.of(
                            DomainEventType.CATERING_ORDER_CONFIRMED,
                            saved.getTenantId(),
                            saved.getId(),
                            Map.of("contactId", String.valueOf(saved.getContactId()),
                                    "headcount", saved.getHeadcount()))));
        });
    }

    /**
     * Transitions the order to {@link CateringOrderStatus#COMPLETED}.
     *
     * @throws DigiPresBeException errorCode 1412 if the order is CANCELLED.
     */
    public Mono<CateringOrder> complete(UUID id) {
        return findById(id).flatMap(order -> {
            if (order.getStatus() == CateringOrderStatus.CANCELLED) {
                return Mono.error(new DigiPresBeException(
                        "Cannot complete a CANCELLED catering order", 1412, 409));
            }
            order.setStatus(CateringOrderStatus.COMPLETED);
            return orders.save(order);
        });
    }

    private static String buildQuoteNotes(CateringOrder order) {
        StringBuilder sb = new StringBuilder("Catering order — ");
        sb.append("headcount: ").append(order.getHeadcount());
        if (order.getEventDate() != null) {
            sb.append(", event date: ").append(order.getEventDate());
        }
        if (order.getDeliveryMode() != null) {
            sb.append(", delivery: ").append(order.getDeliveryMode());
        }
        if (order.getDietaryRequirements() != null && !order.getDietaryRequirements().isBlank()) {
            sb.append("\nDietary requirements: ").append(order.getDietaryRequirements());
        }
        if (order.getSpecialRequests() != null && !order.getSpecialRequests().isBlank()) {
            sb.append("\nSpecial requests: ").append(order.getSpecialRequests());
        }
        return sb.toString();
    }
}
