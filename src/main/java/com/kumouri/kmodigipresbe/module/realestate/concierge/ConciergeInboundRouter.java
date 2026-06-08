package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversationRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosureRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — the RE-side inbound-SMS router the {@code InboundSmsService}
 * {@code smsMode="realestate"} seam delegates to (RE-1 §6.6). The CF-3 inbound webhook + signature
 * boundary are REUSED verbatim; this owns only the realestate routing.
 *
 * <p>STOP-words are handled by the seam (the unchanged shared opt-out) <strong>before</strong> delegation,
 * so this router only ever sees question bodies in RE-1. The flow:
 * <ol>
 *   <li>resolve the {@link Listing} by {@code trackedPhone == To} (fallback: the most-recent conversation
 *       for {@code From} within the TTL window; else reply "Which property?" — {@code 4251} advisory);</li>
 *   <li>find/create the {@link ConciergeConversation} for {@code (tenant, From, listing)};</li>
 *   <li>append the BUYER turn; emit {@code CONCIERGE_INBOUND_RECEIVED};</li>
 *   <li>RE-1 = always treat the body as a question → {@link ListingConciergeService#answer};</li>
 *   <li>handoff → reply "…I've looped in your agent…" + state {@code HANDED_OFF}; else → reply the
 *       grounded answer; append the ASSISTANT turn (with citations) and save.</li>
 * </ol>
 *
 * <p><strong>Best-effort &amp; never 500 the webhook:</strong> all work runs under the synthetic
 * {@code TenantContext(tenantId, null, {INTEGRATION_TWILIO})} (so {@link ConciergeAnswerService} +
 * {@link TwilioSmsService} resolve the tenant), and the {@code handle} chain swallows errors to an
 * {@link Outcome} — the controller acknowledges Twilio with 200 regardless (an AI/SMS failure becomes a
 * graceful handoff, never an HTTP error). Hand-constructed as a {@code @Bean} when the module is enabled.
 */
@Slf4j
public class ConciergeInboundRouter {

    /** What an inbound concierge SMS resolved to — for the seam/controller to log. */
    public enum Outcome { ANSWERED, HANDED_OFF, NO_LISTING, IGNORED }

    private final ListingRepository listings;
    private final ListingDisclosureRepository disclosures;
    private final ConciergeConversationRepository conversations;
    private final ListingConciergeService conciergeService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    private final long correlationTtlMinutes;
    private final boolean handoffNotify;
    private final String handoffSmsBody;
    private final String disambiguationSmsBody;

    public ConciergeInboundRouter(ListingRepository listings,
                                  ListingDisclosureRepository disclosures,
                                  ConciergeConversationRepository conversations,
                                  ListingConciergeService conciergeService,
                                  TwilioSmsService twilioSmsService,
                                  DomainEventPublisher events,
                                  long correlationTtlMinutes,
                                  boolean handoffNotify,
                                  String handoffSmsBody,
                                  String disambiguationSmsBody) {
        this.listings = listings;
        this.disclosures = disclosures;
        this.conversations = conversations;
        this.conciergeService = conciergeService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.correlationTtlMinutes = correlationTtlMinutes;
        this.handoffNotify = handoffNotify;
        this.handoffSmsBody = handoffSmsBody;
        this.disambiguationSmsBody = disambiguationSmsBody;
    }

    /**
     * Entry point the {@code InboundSmsService} seam calls (tenant resolved from the path; STOP already
     * handled). Establishes the synthetic tenant context and routes the body as a single-turn question.
     * Best-effort: any failure resolves to {@link Outcome#IGNORED} (the webhook still 200s).
     */
    public Mono<Outcome> handle(UUID tenantId, String from, String to, String body) {
        if (from == null || from.isBlank() || body == null || body.isBlank()) {
            return Mono.just(Outcome.IGNORED);
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        return resolveListing(tenantId, from, to)
                .flatMap(listing -> handleForListing(tenantId, from, listing, body))
                // Mono.defer so the disambiguation reply (and its eager SMS-mock invocation) only happens
                // on actual subscription — i.e. only when resolveListing was genuinely empty.
                .switchIfEmpty(Mono.defer(() -> noListing(from)))
                .onErrorResume(err -> {
                    log.warn("RE-1 concierge inbound failed (best-effort) for tenant {} from {}: {}",
                            tenantId, from, err.toString());
                    return Mono.just(Outcome.IGNORED);
                })
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /**
     * Resolve the listing for this inbound: primary = the tracked number ({@code To}); fallback = the
     * most-recent conversation for {@code From} within the TTL window (a shared-number tenant). Empty when
     * neither resolves.
     */
    private Mono<Listing> resolveListing(UUID tenantId, String from, String to) {
        Mono<Listing> byTracked = (to == null || to.isBlank())
                ? Mono.empty()
                : listings.findByTenantIdAndTrackedPhone(tenantId, to);
        return byTracked.switchIfEmpty(Mono.defer(() -> recentConversationListing(tenantId, from)));
    }

    /** Fallback: the listing of the most-recent in-window conversation for this buyer phone. */
    private Mono<Listing> recentConversationListing(UUID tenantId, String from) {
        Instant cutoff = Instant.now().minusSeconds(correlationTtlMinutes * 60);
        return conversations.findByTenantIdAndBuyerPhoneOrderByLastInboundAtDesc(tenantId, from)
                .filter(c -> c.getLastInboundAt() != null && c.getLastInboundAt().isAfter(cutoff))
                .next()
                .flatMap(c -> listings.findByIdAndTenantId(c.getListingId(), tenantId));
    }

    private Mono<Outcome> handleForListing(UUID tenantId, String from, Listing listing, String body) {
        return findOrCreateConversation(tenantId, from, listing.getId())
                .flatMap(conv -> {
                    conv.getTurns().add(ConciergeTurn.builder()
                            .role(ConciergeTurn.Role.BUYER)
                            .body(body)
                            .at(Instant.now())
                            .build());
                    conv.setLastInboundAt(Instant.now());
                    return conversations.save(conv)
                            .doOnNext(saved -> events.publish(DomainEvent.of(
                                    DomainEventType.CONCIERGE_INBOUND_RECEIVED, tenantId, saved.getId(),
                                    Map.of("listingId", listing.getId(),
                                            "conversationId", saved.getId(),
                                            "buyerPhone", from))));
                })
                .flatMap(conv -> answerAndReply(tenantId, from, listing, conv, body));
    }

    private Mono<Outcome> answerAndReply(UUID tenantId, String from, Listing listing,
                                         ConciergeConversation conv, String question) {
        return conciergeService.answer(tenantId, listing.getId(), question)
                .flatMap(answer -> {
                    if (answer.handoff()) {
                        return reply(from, handoffSmsBody)
                                .then(appendAssistant(conv, handoffSmsBody, true, List.of(),
                                        ConversationState.HANDED_OFF))
                                .then(notifyAgent(listing, from, question))
                                .thenReturn(Outcome.HANDED_OFF);
                    }
                    return resolveCitations(tenantId, answer.citations())
                            .flatMap(turnCitations -> reply(from, answer.answer())
                                    .then(appendAssistant(conv, answer.answer(), false, turnCitations,
                                            ConversationState.ASKING))
                                    .thenReturn(Outcome.ANSWERED));
                });
    }

    /**
     * Resolve the human-friendly {@code disclosureType} for each cited disclosure from the persisted
     * {@link ListingDisclosure} (the RAG chunk carries only sourceType/sourceId; the category lives on the
     * row). Best-effort — a missing disclosure degrades to the source-type label, never drops the citation.
     */
    private Mono<List<ConciergeTurn.TurnCitation>> resolveCitations(
            UUID tenantId, List<ListingConciergeService.Citation> citations) {
        // Flux...collectList (not Mono.zip) so 0, 1, and N citations all resolve cleanly — Mono.zip emits
        // empty for a single source, which would silently drop a lone citation.
        return Flux.fromIterable(citations)
                .concatMap(c -> disclosures.findByIdAndTenantId(c.disclosureId(), tenantId)
                        .map(d -> ConciergeTurn.TurnCitation.builder()
                                .disclosureId(c.disclosureId())
                                .disclosureType(d.getDisclosureType() != null
                                        ? d.getDisclosureType().name() : "GENERAL")
                                .contentPreview(c.contentPreview())
                                .score(c.score())
                                .build())
                        .defaultIfEmpty(ConciergeTurn.TurnCitation.builder()
                                .disclosureId(c.disclosureId())
                                .disclosureType("LISTING_DISCLOSURE")
                                .contentPreview(c.contentPreview())
                                .score(c.score())
                                .build()))
                .collectList();
    }

    private Mono<ConciergeConversation> appendAssistant(ConciergeConversation conv, String body,
                                                        boolean handoff,
                                                        List<ConciergeTurn.TurnCitation> citations,
                                                        ConversationState newState) {
        conv.getTurns().add(ConciergeTurn.builder()
                .role(ConciergeTurn.Role.ASSISTANT)
                .body(body)
                .at(Instant.now())
                .handoff(handoff)
                .citations(citations)
                .build());
        conv.setState(newState);
        return conversations.save(conv);
    }

    private Mono<ConciergeConversation> findOrCreateConversation(UUID tenantId, String from, UUID listingId) {
        return conversations.findByTenantIdAndBuyerPhoneAndListingId(tenantId, from, listingId)
                .switchIfEmpty(Mono.defer(() -> conversations.save(ConciergeConversation.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .listingId(listingId)
                        .buyerPhone(from)
                        .state(ConversationState.ASKING)
                        .turns(new ArrayList<>())
                        .build())));
    }

    /** Reply to the buyer; best-effort (an SMS failure is logged, never propagated). */
    private Mono<Void> reply(String to, String bodyText) {
        return twilioSmsService.sendSms(SmsCommunicationRequest.builder()
                        .to(PhoneContact.builder().e164(to).build())
                        .body(bodyText)
                        .build())
                .doOnError(err -> log.warn("RE-1 concierge: reply SMS to {} failed: {}", to, err.toString()))
                .onErrorReturn(false)
                .then();
    }

    /** Best-effort agent notify on a handoff (gated by {@code kmosf.realestate.handoff-notify}). */
    private Mono<Void> notifyAgent(Listing listing, String from, String question) {
        if (!handoffNotify) {
            return Mono.empty();
        }
        log.info("RE-1 concierge: HANDOFF for listing {} — buyer {} asked '{}' (agent notify)",
                listing.getId(), from, question);
        // The owner-user/SMS notify path is layered in RE-2 (hot-handoff); RE-1 logs the handoff so the
        // transcript + this log are the agent signal. Kept as a best-effort no-throw seam.
        return Mono.empty();
    }

    private Mono<Outcome> noListing(String from) {
        return reply(from, disambiguationSmsBody).thenReturn(Outcome.NO_LISTING)
                .doOnSubscribe(s -> log.debug(
                        "RE-1 concierge: could not resolve a listing for inbound from {} (4251)", from));
    }
}
