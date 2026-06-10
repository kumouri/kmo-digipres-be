package com.kumouri.kmodigipresbe.module.quoting.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — Q3: the accept → book step. When a homeowner accepts their instant
 * quote, this texts them a booking link so they can pick a confirming-visit time and hands the office
 * a pre-qualified job.
 *
 * <h2>Booking link, not live Cal.com (§7)</h2>
 * The booking link is the tenant's {@code IntegrationConnection(twilio).config["bookingLink"]} (a
 * configured string — e.g. their Cal.com event URL), texted via the reused
 * {@link TwilioSmsService#sendSms}. There is <strong>no live Cal.com call</strong> in the loop (the
 * {@code ShowingBookingService} / T5 posture). A future live Cal.com booking is a clean swap behind
 * this method; going live needs A2P 10DLC for the caller-facing SMS (a separate human action).
 *
 * <h2>Idempotent accept (explicit-boolean — never {@code switchIfEmpty})</h2>
 * Load the quote tenant-scoped ({@code 4435} if absent). An <strong>explicit-boolean</strong> status
 * check: a quote already ACCEPTED/BOOKED re-confirms (re-sends nothing — no second SMS, no second
 * status write) and returns it; a DECLINED quote cannot be accepted ({@code 4436}); a NEW quote moves
 * to ACCEPTED, stamps {@code acceptedAt} + the {@code bookingLinkSent}, and texts the link. The SMS is
 * best-effort — a send failure never fails the accept (the status transition is the durable effect).
 *
 * <h2>Optional Documenso SOW on REPLACE (documented seam — NOT wired live, §7)</h2>
 * On a REPLACE acceptance a tenant may want a draft Statement of Work generated; that reuses the
 * shipped {@code ContractService.spawnFromQuote} contract path (no live Documenso) behind a per-tenant
 * flag. It is intentionally NOT wired in this BE leg (it needs a materialized {@code Quote} + an
 * active SOW {@code ContractTemplate}); it is a documented go-live opt-in.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code QuotingAutoConfiguration} when the module is on.
 * Runs under the synthetic widget {@code TenantContext} the orchestrator establishes from the token.
 */
@Slf4j
public class QuoteBookingService {

    private static final String NOTIFY_PROVIDER = TwilioSmsService.PROVIDER; // "twilio"
    private static final String BOOKING_LINK_KEY = "bookingLink";

    private final QuoteRequestRepository quotes;
    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    public QuoteBookingService(QuoteRequestRepository quotes,
                               IntegrationConnectionRepository connections,
                               TwilioSmsService twilioSmsService,
                               DomainEventPublisher events) {
        this.quotes = quotes;
        this.connections = connections;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
    }

    /**
     * Accept the quote {@code quoteId} for {@code tenantId} and text the booking link. Idempotent: an
     * already-accepted quote re-confirms with no second SMS. {@code 4435} if not found; {@code 4436}
     * if DECLINED (cannot accept).
     */
    public Mono<QuoteRequest> accept(UUID tenantId, UUID quoteId) {
        return quotes.findByTenantIdAndId(tenantId, quoteId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Quote not found", 4435, 404)))
                .flatMap(quote -> {
                    // Already accepted/booked → idempotent re-confirm, no second effect.
                    if (quote.getStatus() == QuoteStatus.ACCEPTED
                            || quote.getStatus() == QuoteStatus.BOOKED) {
                        return Mono.just(quote);
                    }
                    if (quote.getStatus() == QuoteStatus.DECLINED) {
                        return Mono.error(new DigiPresBeException(
                                "Quote has been declined and cannot be accepted", 4436, 409));
                    }
                    return resolveBookingLink(tenantId)
                            .flatMap(bookingLink -> {
                                quote.setStatus(QuoteStatus.ACCEPTED);
                                quote.setAcceptedAt(Instant.now());
                                quote.setBookingLinkSent(bookingLink);
                                return quotes.save(quote)
                                        .flatMap(saved -> sendBookingSms(saved, bookingLink)
                                                .then(Mono.fromRunnable(() ->
                                                        emitAccepted(tenantId, saved)))
                                                .thenReturn(saved));
                            });
                });
    }

    /** The tenant's configured booking link (null/empty when unset — the SMS then just confirms). */
    private Mono<String> resolveBookingLink(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, NOTIFY_PROVIDER)
                .map(conn -> {
                    Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
                    return config.getOrDefault(BOOKING_LINK_KEY, "");
                })
                .defaultIfEmpty("");
    }

    /**
     * Best-effort booking-link SMS to the homeowner (a send failure never fails the accept — the
     * status transition is already durable). Skipped when no contact phone is on the quote.
     */
    private Mono<Void> sendBookingSms(QuoteRequest quote, String bookingLink) {
        String to = quote.getContactPhone();
        if (to == null || to.isBlank()) {
            return Mono.empty();
        }
        String body = (bookingLink != null && !bookingLink.isBlank())
                ? "Great — thanks for accepting your estimate! Book your visit here: " + bookingLink
                : "Great — thanks for accepting your estimate! We'll reach out shortly to schedule your visit.";
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(PhoneContact.builder().e164(to).build())
                .body(body)
                .build();
        return twilioSmsService.sendSms(req)
                .doOnError(e -> log.warn("QuoteNow booking-link SMS to {} failed (best-effort, "
                        + "ignored): {}", to, e.getMessage()))
                .onErrorReturn(false)
                .then();
    }

    private void emitAccepted(UUID tenantId, QuoteRequest quote) {
        Map<String, Object> payload = new HashMap<>();
        if (quote.getId() != null) payload.put("quoteRequestId", quote.getId().toString());
        if (quote.getContactId() != null) payload.put("contactId", quote.getContactId().toString());
        if (quote.getRepairVsReplace() != null && quote.getRepairVsReplace().getRecommendation() != null) {
            payload.put("recommendation", quote.getRepairVsReplace().getRecommendation().name());
        }
        payload.put("bookingLinkSent", quote.getBookingLinkSent() != null
                && !quote.getBookingLinkSent().isBlank());
        events.publish(DomainEvent.of(
                DomainEventType.QUOTE_ACCEPTED, tenantId, quote.getId(), payload));
    }
}
