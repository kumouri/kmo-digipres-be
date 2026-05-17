package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.model.response.PortalQuoteSummary;
import com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver;
import com.kumouri.kmodigipresbe.service.portal.PortalOwnershipGuard;
import com.kumouri.kmodigipresbe.service.portal.PortalQuotesService;
import com.kumouri.kmodigipresbe.service.quote.QuoteService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Portal quotes surface (Phase G — G.4, G-D1, G-D5).
 *
 * <p>All data funnels through {@link PortalLinkedContactResolver} (list) or
 * {@link PortalOwnershipGuard#requireOwnedQuote} (single). No
 * {@code @ConditionalOnProperty} — the portal chain is the gate.
 *
 * <h2>Accept / Decline (G-D5)</h2>
 * The allow-list enforces that only {@code SENT → ACCEPTED} / {@code SENT → DECLINED}
 * transitions are permitted from the portal (errorCode {@code 3805 / 409}).
 * Status mutation delegates to the unchanged {@link QuoteService#setStatus} —
 * no new QuoteService method. Portal-accept does NOT auto-spawn a contract
 * (Phase F's {@code spawnFromQuote} stays an explicit, separate, staff action — G-D5).
 *
 * <p>Advisory events {@link DomainEventType#PORTAL_QUOTE_ACCEPTED} and
 * {@link DomainEventType#PORTAL_QUOTE_DECLINED} are published after a successful
 * status transition.
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalQuotesController {

    private final PortalLinkedContactResolver linkedContact;
    private final PortalQuotesService portalQuotesService;
    private final PortalOwnershipGuard ownershipGuard;
    private final QuoteService quoteService;
    private final DomainEventPublisher events;

    @GetMapping("/quotes")
    public Flux<PortalQuoteSummary> listQuotes() {
        return linkedContact.resolve()
                .flatMapMany(portalQuotesService::listForContact);
    }

    @GetMapping("/quotes/{id}")
    public Mono<PortalQuoteSummary> getQuote(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedQuote(id)
                .map(PortalQuoteSummary::from);
    }

    /**
     * Accept a SENT quote from the portal. Allow-list: only {@code SENT → ACCEPTED}
     * is permitted; any other current status returns {@code 3805 / 409}.
     * Delegates status mutation to the unchanged {@link QuoteService#setStatus}.
     * Does NOT auto-spawn a contract (G-D5).
     */
    @PostMapping("/quotes/{id}/accept")
    public Mono<PortalQuoteSummary> acceptQuote(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedQuote(id)
                .flatMap(quote -> {
                    if (quote.getStatus() != Quote.Status.SENT) {
                        return Mono.error(new DigiPresBeException(
                                "Only SENT quotes can be accepted from the portal", 3805, 409));
                    }
                    return quoteService.setStatus(quote.getId(), Quote.Status.ACCEPTED)
                            .doOnSuccess(updated -> {
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("quoteId", updated.getId().toString());
                                payload.put("contactId", updated.getContactId() == null
                                        ? null : updated.getContactId().toString());
                                events.publish(DomainEvent.of(
                                        DomainEventType.PORTAL_QUOTE_ACCEPTED,
                                        updated.getTenantId(),
                                        updated.getId(),
                                        payload));
                            });
                })
                .map(PortalQuoteSummary::from);
    }

    /**
     * Decline a SENT quote from the portal. Allow-list: only {@code SENT → DECLINED}
     * is permitted; any other current status returns {@code 3805 / 409}.
     * Delegates status mutation to the unchanged {@link QuoteService#setStatus}.
     */
    @PostMapping("/quotes/{id}/decline")
    public Mono<PortalQuoteSummary> declineQuote(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedQuote(id)
                .flatMap(quote -> {
                    if (quote.getStatus() != Quote.Status.SENT) {
                        return Mono.error(new DigiPresBeException(
                                "Only SENT quotes can be declined from the portal", 3805, 409));
                    }
                    return quoteService.setStatus(quote.getId(), Quote.Status.DECLINED)
                            .doOnSuccess(updated -> {
                                Map<String, Object> payload = new HashMap<>();
                                payload.put("quoteId", updated.getId().toString());
                                payload.put("contactId", updated.getContactId() == null
                                        ? null : updated.getContactId().toString());
                                events.publish(DomainEvent.of(
                                        DomainEventType.PORTAL_QUOTE_DECLINED,
                                        updated.getTenantId(),
                                        updated.getId(),
                                        payload));
                            });
                })
                .map(PortalQuoteSummary::from);
    }
}
