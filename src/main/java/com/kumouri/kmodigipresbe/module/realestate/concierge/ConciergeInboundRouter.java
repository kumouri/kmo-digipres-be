package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.realestate.model.BuyerQualification;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversationRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosureRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.module.realestate.responder.ResponderHandoffDelegate;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.Nullable;
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
 * <p><strong>RE-2 — multi-turn qualification (additive, best-effort).</strong> After the grounded
 * answer/handoff is sent + the turn is persisted (the RE-1 path is byte-equivalent), the router runs a
 * {@link QualificationExtractionService} extraction over the conversation-so-far and accumulates
 * {@code budget/timeline/financing/intent} via {@link QualificationService} — which, on enough signal,
 * materializes the buyer {@code Contact} + a concierge-sourced {@code Deal} the unchanged nightly
 * {@code LeadScoringV2Service} tiers (the hot-handoff then fires off {@code LEAD_SCORE_UPDATED}). The
 * qualification step is wrapped {@code onErrorResume} and never changes the answer/handoff outcome: a
 * Claude/extraction failure is swallowed (advisory {@code 4260}) so a question still gets its cited answer.
 * On a {@code HANDED_OFF} turn the state stays {@code HANDED_OFF}; otherwise the state becomes
 * {@code QUALIFYING} once a Deal is materialized (else stays {@code ASKING}).
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
    public enum Outcome { ANSWERED, HANDED_OFF, BOOKING_OFFERED, BOOKED, NO_LISTING, IGNORED }

    private final ListingRepository listings;
    private final ListingDisclosureRepository disclosures;
    private final ConciergeConversationRepository conversations;
    private final ListingConciergeService conciergeService;
    private final QualificationExtractionService qualificationExtraction;
    private final QualificationService qualificationService;
    private final ShowingBookingService showingBookingService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    private final long correlationTtlMinutes;
    private final boolean handoffNotify;
    private final String handoffSmsBody;
    private final String disambiguationSmsBody;
    /**
     * Security fix BE-12 — when true (default), an AI concierge answer that trips the deterministic
     * {@code FairHousingLint} is NOT auto-sent: the turn is forced to a safe HANDOFF (the handoff
     * template + {@code HANDED_OFF} state + agent notify) instead. This is the output backstop the
     * RE-4 marketing path already has; the concierge auto-sends so it must force a safe outcome
     * rather than merely flag for a human.
     */
    private final boolean fairHousingBlock;

    /**
     * T3 (Midnight Responder) — the off-listing / unknown-intent handoff delegate (the E2 responder
     * default-handoff). Wired (via {@link #setResponderHandoff}) only when BOTH the realestate AND responder
     * modules are loaded; <strong>null otherwise → byte-identical RE-1</strong> (the disambiguation /
     * handoff reply is the only effect, exactly as before T3). The bean is hand-constructed (not
     * component-scanned), so this is a setter the T3 auto-config invokes, not field-{@code @Autowired} —
     * exactly the {@code setConciergeRouter} / {@code setIntentRouter} precedents.
     */
    @Nullable
    private ResponderHandoffDelegate responderHandoff;

    public ConciergeInboundRouter(ListingRepository listings,
                                  ListingDisclosureRepository disclosures,
                                  ConciergeConversationRepository conversations,
                                  ListingConciergeService conciergeService,
                                  QualificationExtractionService qualificationExtraction,
                                  QualificationService qualificationService,
                                  ShowingBookingService showingBookingService,
                                  TwilioSmsService twilioSmsService,
                                  DomainEventPublisher events,
                                  long correlationTtlMinutes,
                                  boolean handoffNotify,
                                  String handoffSmsBody,
                                  String disambiguationSmsBody,
                                  boolean fairHousingBlock) {
        this.listings = listings;
        this.disclosures = disclosures;
        this.conversations = conversations;
        this.conciergeService = conciergeService;
        this.qualificationExtraction = qualificationExtraction;
        this.qualificationService = qualificationService;
        this.showingBookingService = showingBookingService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.correlationTtlMinutes = correlationTtlMinutes;
        this.handoffNotify = handoffNotify;
        this.handoffSmsBody = handoffSmsBody;
        this.disambiguationSmsBody = disambiguationSmsBody;
        this.fairHousingBlock = fairHousingBlock;
    }

    /**
     * T3 (Midnight Responder) — wire the off-listing / unknown-intent handoff delegate. Invoked by
     * {@code RealEstateMidnightAutoConfiguration} only when BOTH realestate + responder are enabled; never
     * called otherwise (the seam stays inert and RE-1 is byte-identical). Idempotent / last-wins.
     */
    public void setResponderHandoff(@Nullable ResponderHandoffDelegate responderHandoff) {
        this.responderHandoff = responderHandoff;
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
                .switchIfEmpty(Mono.defer(() -> noListing(tenantId, from, to, body)))
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
                    Instant received = Instant.now();
                    conv.getTurns().add(ConciergeTurn.builder()
                            .role(ConciergeTurn.Role.BUYER)
                            .body(body)
                            .at(received)
                            // T3 (Midnight Responder) — stamp inbound-receipt for the received→replied
                            // latency the assistant turn computes (the "<30s, 24/7" demo stat).
                            .receivedAt(received)
                            .build());
                    conv.setLastInboundAt(received);
                    return conversations.save(conv)
                            .doOnNext(saved -> events.publish(DomainEvent.of(
                                    DomainEventType.CONCIERGE_INBOUND_RECEIVED, tenantId, saved.getId(),
                                    Map.of("listingId", listing.getId(),
                                            "conversationId", saved.getId(),
                                            "buyerPhone", from))));
                })
                .flatMap(conv -> route(tenantId, from, listing, conv, body));
    }

    /**
     * Multi-turn routing of the (already-persisted) buyer turn (RE-3 §5 / decision 4). The state machine:
     * <ul>
     *   <li><strong>{@code OFFERING_SLOTS}</strong> → the body is a slot pick → {@link
     *       ShowingBookingService#book} (writes the {@code Meeting}, advances to {@code BOOKED}, confirms);
     *       <em>not</em> the grounded-answer path (a "2" is not a disclosure question).</li>
     *   <li><strong>else + {@link ShowingBookingService#hasShowingIntent showing intent}</strong> (a cheap
     *       keyword pre-filter — no model call, so the RE-1 call-count gate holds) → {@link
     *       ShowingBookingService#offerSlots} (offer slots, advance to {@code OFFERING_SLOTS}).</li>
     *   <li><strong>else</strong> → the unchanged RE-1 grounded-answer + RE-2 qualification path ({@link
     *       #answerAndReply}). A pure factual question routes here exactly as before — byte-equivalent.</li>
     * </ul>
     * All branches are best-effort (the {@code handle} chain swallows errors to {@link Outcome#IGNORED}).
     */
    private Mono<Outcome> route(UUID tenantId, String from, Listing listing,
                                ConciergeConversation conv, String body) {
        if (conv.getState() == ConversationState.OFFERING_SLOTS) {
            // A pick for the slots we already offered — book it (or re-offer on a no-match).
            return showingBookingService.book(tenantId, listing, conv, from, body)
                    .map(saved -> saved.getState() == ConversationState.BOOKED
                            ? Outcome.BOOKED : Outcome.BOOKING_OFFERED);
        }
        if (ShowingBookingService.hasShowingIntent(body)) {
            // The buyer wants to see it — offer showing slots (no grounded-answer model call this turn).
            return showingBookingService.offerSlots(tenantId, listing, conv, from)
                    .thenReturn(Outcome.BOOKING_OFFERED);
        }
        // RE-1 grounded answer + RE-2 qualification — unchanged.
        return answerAndReply(tenantId, from, listing, conv, body);
    }

    private Mono<Outcome> answerAndReply(UUID tenantId, String from, Listing listing,
                                         ConciergeConversation conv, String question) {
        return conciergeService.answer(tenantId, listing.getId(), question)
                .flatMap(answer -> {
                    if (answer.handoff()) {
                        return handoffReply(tenantId, from, listing, conv, question);
                    }
                    // Security fix BE-12: deterministic Fair-Housing backstop on the OUTBOUND answer
                    // (the buyer text is attacker-controlled, so a crafted prompt could steer the model
                    // into steering/protected-class language). Unlike the marketing path — which flags
                    // for a human who still approves — the concierge AUTO-SENDS, so a flagged answer is
                    // suppressed and the turn is forced to a safe HANDOFF rather than being texted back.
                    String riskTerm = fairHousingBlock
                            ? com.kumouri.kmodigipresbe.module.realestate.marketing.FairHousingLint
                                    .firstRiskTerm(answer.answer())
                            : null;
                    if (riskTerm != null) {
                        log.warn("RE-1 concierge: Fair-Housing lint flagged the AI answer (term='{}') for "
                                + "listing {} — suppressing the answer and forcing HANDOFF (BE-12)",
                                riskTerm, listing.getId());
                        return handoffReply(tenantId, from, listing, conv, question);
                    }
                    return resolveCitations(tenantId, answer.citations())
                            .flatMap(turnCitations -> reply(from, answer.answer())
                                    .then(appendAssistant(conv, answer.answer(), false, turnCitations,
                                            ConversationState.ASKING))
                                    .flatMap(savedConv -> qualifyAndPersist(tenantId, listing, savedConv,
                                            false))
                                    .thenReturn(Outcome.ANSWERED));
                });
    }

    /**
     * The shared HANDOFF outcome (security fix BE-12 reuses this for a Fair-Housing-flagged answer):
     * reply the handoff template, append the HANDED_OFF assistant turn, still run RE-2 qualification
     * (keeping HANDED_OFF), and best-effort agent-notify + T3 responder delegation. Identical to the
     * original {@code answer.handoff()} branch — extracted so a model-handoff and a lint-forced handoff
     * take exactly the same safe path.
     */
    private Mono<Outcome> handoffReply(UUID tenantId, String from, Listing listing,
                                       ConciergeConversation conv, String question) {
        return reply(from, handoffSmsBody)
                .then(appendAssistant(conv, handoffSmsBody, true, List.of(),
                        ConversationState.HANDED_OFF))
                .flatMap(savedConv -> notifyAgent(listing, from, question)
                        .then(qualifyAndPersist(tenantId, listing, savedConv, true)))
                .flatMap(c -> delegateHandoff(tenantId, from, question).thenReturn(c))
                .thenReturn(Outcome.HANDED_OFF);
    }

    /**
     * RE-2 qualification step — runs AFTER the grounded answer/handoff turn is persisted (so the RE-1 path
     * is byte-equivalent). Extracts the buyer's {@code budget/timeline/financing/intent} from the
     * conversation-so-far and accumulates it via {@link QualificationService} (which materializes the buyer
     * {@code Contact} + concierge {@code Deal} on enough signal). Best-effort: a Claude/extraction failure
     * is swallowed ({@code 4260}) — the answer/handoff already happened and is durable, the conversation is
     * never dropped. When a Deal materializes and the turn was not a handoff, the state advances to
     * {@code QUALIFYING}; a handoff turn keeps {@code HANDED_OFF}.
     *
     * <p><strong>Cheap pre-filter (cost + RE-1 byte-equivalence).</strong> The Claude extraction only fires
     * when the conversation actually carries a qualification signal ({@link #hasQualificationSignal}) — a
     * pure factual question ("how old is the roof?") never triggers an extraction call, so it is both cheap
     * (no LLM per inbound factual Q) and keeps the RE-1 grounding path's model-call behavior byte-identical
     * (the {@code RealEstateConciergeIT} call-count gate). A signal-bearing turn (budget / financing /
     * timeline / buy-sell language) runs the extraction.
     */
    private Mono<ConciergeConversation> qualifyAndPersist(UUID tenantId, Listing listing,
                                                         ConciergeConversation conv, boolean handoffTurn) {
        if (!conversationHasSignal(conv)) {
            // Nothing qualification-shaped was said — skip the extraction entirely (no model call).
            return Mono.just(conv);
        }
        return qualificationExtraction.extract(conv.getTurns())
                .flatMap(extracted -> qualificationService.qualify(tenantId, listing, conv, extracted))
                .flatMap(qualified -> {
                    BuyerQualification q = qualified.getQualification();
                    boolean materialized = q != null && q.isDealMaterialized();
                    if (materialized && !handoffTurn
                            && qualified.getState() == ConversationState.ASKING) {
                        qualified.setState(ConversationState.QUALIFYING);
                    }
                    return conversations.save(qualified);
                })
                .onErrorResume(err -> {
                    log.warn("RE-2 concierge: qualification step failed (best-effort, 4260) for "
                            + "conversation {}: {}", conv.getId(), err.toString());
                    return Mono.just(conv);
                });
    }

    /** True when any BUYER turn carries a qualification signal — gates the (paid) extraction call. */
    private static boolean conversationHasSignal(ConciergeConversation conv) {
        if (conv.getTurns() == null) {
            return false;
        }
        return conv.getTurns().stream()
                .filter(t -> t.getRole() == ConciergeTurn.Role.BUYER)
                .anyMatch(t -> hasQualificationSignal(t.getBody()));
    }

    /**
     * Cheap, deterministic heuristic: does this buyer text plausibly contain budget / financing / timeline /
     * buy-sell signal worth running an extraction over? Keeps a pure factual disclosure question (no money /
     * timeline / intent words) from ever triggering a (paid) Claude qualification call. Intentionally
     * permissive — the extraction itself is the precise step; this just avoids the obviously-pointless call.
     */
    static boolean hasQualificationSignal(String body) {
        if (body == null || body.isBlank()) {
            return false;
        }
        String s = body.toLowerCase();
        if (s.contains("$")) {
            return true;
        }
        // money / budget vocabulary
        if (s.contains("budget") || s.contains("afford") || s.contains("price range")
                || s.contains("pre-approv") || s.contains("preapprov") || s.contains("pre approv")
                || s.contains("approved") || s.contains("financ") || s.contains("mortgage")
                || s.contains("lender") || s.contains("loan") || s.contains("cash")
                || s.contains("down payment")) {
            return true;
        }
        // intent vocabulary
        if (s.contains("looking to buy") || s.contains("want to buy") || s.contains("looking to sell")
                || s.contains("want to sell") || s.contains("make an offer") || s.contains("offer on")) {
            return true;
        }
        // timeline vocabulary
        if (s.contains("timeline") || s.contains("move in") || s.contains("closing")
                || s.contains("no rush") || s.contains("asap")) {
            return true;
        }
        // a money-shaped number: digits followed by k/m, or a 4+ digit figure, or "<n> days/weeks/months"
        if (s.matches(".*\\b\\d+(\\.\\d+)?\\s*[km]\\b.*")
                || s.matches(".*\\b\\d{4,}\\b.*")
                || s.matches(".*\\b\\d+\\s*(day|days|week|weeks|month|months)\\b.*")) {
            return true;
        }
        return false;
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
        Instant repliedAt = Instant.now();
        // T3 (Midnight Responder) — the received→replied latency, paired off the most-recent BUYER turn's
        // inbound-receipt time. Null-safe: a legacy/unstamped buyer turn ⇒ null latency (no stat recorded).
        Instant receivedAt = lastBuyerReceivedAt(conv);
        Long latencyMs = (receivedAt == null) ? null
                : Math.max(0L, java.time.Duration.between(receivedAt, repliedAt).toMillis());
        conv.getTurns().add(ConciergeTurn.builder()
                .role(ConciergeTurn.Role.ASSISTANT)
                .body(body)
                .at(repliedAt)
                .handoff(handoff)
                .citations(citations)
                .receivedAt(receivedAt)
                .latencyMs(latencyMs)
                .build());
        conv.setState(newState);
        return conversations.save(conv);
    }

    /** The inbound-receipt instant of the most-recent BUYER turn (for the assistant-turn latency), or null. */
    private static Instant lastBuyerReceivedAt(ConciergeConversation conv) {
        if (conv.getTurns() == null) {
            return null;
        }
        for (int i = conv.getTurns().size() - 1; i >= 0; i--) {
            ConciergeTurn t = conv.getTurns().get(i);
            if (t.getRole() == ConciergeTurn.Role.BUYER) {
                return t.getReceivedAt() != null ? t.getReceivedAt() : t.getAt();
            }
        }
        return null;
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

    /**
     * T3 (Midnight Responder) — best-effort HANDOFF delegation to the E2 responder default-handoff. The
     * delegate itself gates on the per-tenant {@code delegateHandoffToResponder} flag (NO_LISTING vs
     * HANDOFF policy lives in the delegate). Null delegate ⇒ no-op (byte-identical RE-1). Never propagates.
     */
    private Mono<Void> delegateHandoff(UUID tenantId, String from, String body) {
        if (responderHandoff == null) {
            return Mono.empty();
        }
        return responderHandoff.delegate(tenantId, from, null, body, ResponderHandoffDelegate.Reason.HANDOFF)
                .onErrorReturn(false)
                .then();
    }

    /**
     * No listing resolved for this inbound. RE-1 behavior: reply the disambiguation template. T3 (Midnight
     * Responder) additive: when the responder handoff is wired, ALSO delegate to the E2 default-handoff
     * (staff notify + generic follow-up reply) so an off-listing buyer doesn't die at a disambiguation
     * prompt — best-effort, never changes the {@code NO_LISTING} outcome. Null delegate ⇒ byte-identical
     * RE-1 (the disambiguation reply only).
     */
    private Mono<Outcome> noListing(UUID tenantId, String from, String to, String body) {
        Mono<Boolean> delegated = responderHandoff == null
                ? Mono.just(false)
                : responderHandoff.delegate(tenantId, from, to, body, ResponderHandoffDelegate.Reason.NO_LISTING)
                        .onErrorReturn(false);
        return reply(from, disambiguationSmsBody)
                .then(delegated)
                .thenReturn(Outcome.NO_LISTING)
                .doOnSubscribe(s -> log.debug(
                        "RE-1 concierge: could not resolve a listing for inbound from {} (4251)", from));
    }
}
