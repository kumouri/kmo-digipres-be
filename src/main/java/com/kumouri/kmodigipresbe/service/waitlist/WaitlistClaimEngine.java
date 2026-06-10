package com.kumouri.kmodigipresbe.service.waitlist;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineEntryRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineOfferRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E4 — the <strong>double-YES-correct</strong> heart of the gap-fill engine. The vertical-agnostic sibling
 * of ChairFill CF-3's {@code WaitlistClaimService} (which stays byte-equivalent): it resolves the
 * most-recent un-expired {@code OFFERED} {@link WaitlistOffer} for an inbound YES and atomically claims the
 * freed <em>slot</em> for the first replier; every later YES for that same slot gets an apologetic
 * auto-reply and creates no domain record. The one divergence from CF-3 is the winner path: instead of
 * calling the salon {@code SalonBookingService.create}, it delegates the real domain-record creation to a
 * pluggable {@link SlotMaterializer} (resolved by {@code slotType}) — keeping the engine vertical-agnostic.
 *
 * <h2>The atomic claim (the one piece that MUST be correct — verbatim CF-3 D2)</h2>
 * The contended resource is "the freed slot," which no single existing document uniquely represents — so
 * we mint a per-slot {@code waitlist_slot_claims} doc keyed {@code _id = "<tenantId>:<slotKey>"} and claim
 * it with a single conditional {@link ReactiveMongoTemplate#findAndModify} — the
 * {@code WorkOrderNumberGenerator} precedent:
 * <pre>{@code
 *   query  = { _id: slotKey, claimedByContactId: null }   // the unclaimed guard — the race gate
 *   update = { $set: { claimedByContactId: X, claimedOfferId: offerId, claimedAt: now } }
 *   opts   = findAndModify().returnNew(true).upsert(true)
 * }</pre>
 * Mongo single-document updates are atomic, so <strong>exactly one</strong> concurrent YES finds the doc
 * unclaimed and writes it (winner — gets the modified doc back, claimed by itself). For every other
 * concurrent YES the {@code claimedByContactId:null} predicate no longer matches, so {@code upsert:true}
 * attempts to INSERT a second doc with the same unique {@code _id} → {@code DuplicateKeyException} → the
 * loser. <strong>First-writer-wins, atomically, at the database.</strong> (NEVER {@code switchIfEmpty(claim)}
 * — the no-open-offer path is {@code defaultIfEmpty}; the claim is the atomic op.)
 *
 * <p><strong>Winner:</strong> reconstruct the {@link WaitlistSlot} from the offer snapshot, dispatch to the
 * matching {@link SlotMaterializer} (the consumer creates the real record; the no-op fallback creates
 * nothing), mark the offer {@code CLAIMED}, mark sibling offers {@code SUPERSEDED}, mark the entry
 * {@code FULFILLED}, send a confirmation SMS, and emit {@code WAITLIST_ENGINE_SLOT_CLAIMED}.
 *
 * <p><strong>Loser(s):</strong> the slot was already claimed → an apologetic, time-boxed auto-reply. No
 * record, no error surfaced. Best-effort throughout: a materializer/SMS hiccup never corrupts state.
 */
@Slf4j
public class WaitlistClaimEngine {

    private static final String CLAIMS_COLLECTION = "waitlist_slot_claims";

    private final ReactiveMongoTemplate mongo;
    private final WaitlistEngineOfferRepository offers;
    private final WaitlistEngineEntryRepository entries;
    private final TwilioSmsService twilioSms;
    private final DomainEventPublisher events;
    private final List<SlotMaterializer> materializers;
    private final SlotMaterializer noOpMaterializer;

    private final String confirmationTemplate;
    private final String apologyTemplate;

    public WaitlistClaimEngine(ReactiveMongoTemplate mongo,
                               WaitlistEngineOfferRepository offers,
                               WaitlistEngineEntryRepository entries,
                               TwilioSmsService twilioSms,
                               DomainEventPublisher events,
                               List<SlotMaterializer> materializers,
                               SlotMaterializer noOpMaterializer,
                               String confirmationTemplate,
                               String apologyTemplate) {
        this.mongo = mongo;
        this.offers = offers;
        this.entries = entries;
        this.twilioSms = twilioSms;
        this.events = events;
        this.materializers = materializers == null ? List.of() : materializers;
        this.noOpMaterializer = noOpMaterializer;
        this.confirmationTemplate = blankTo(confirmationTemplate, "You're booked! See you soon.");
        this.apologyTemplate = blankTo(apologyTemplate,
                "Sorry — that slot was just taken. You're still first in line for the next opening!");
    }

    /** Outcome of an inbound YES — for the consumer's inbound-SMS path to log / branch on. */
    public enum ClaimOutcome { WON, LOST, NO_OPEN_OFFER }

    /**
     * Handle an inbound affirmative ("YES") from {@code fromPhone} for {@code tenantId}: correlate to the
     * most-recent un-expired {@code OFFERED} offer for that phone, then run the atomic claim. Returns the
     * outcome. Establishes the synthetic {@code TenantContext} so the materializer + repos run tenant-scoped.
     */
    public Mono<ClaimOutcome> claim(UUID tenantId, String fromPhone) {
        if (fromPhone == null || fromPhone.isBlank()) {
            return Mono.just(ClaimOutcome.NO_OPEN_OFFER);
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return resolveOpenOffer(tenantId, fromPhone)
                .flatMap(offer -> claimSlot(tenantId, offer))
                .defaultIfEmpty(ClaimOutcome.NO_OPEN_OFFER)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /** The most-recent still-{@code OFFERED}, un-expired offer for this tenant + phone (or empty). */
    private Mono<WaitlistOffer> resolveOpenOffer(UUID tenantId, String fromPhone) {
        Instant now = Instant.now();
        return offers.findByTenantIdAndContactPhoneAndStatusOrderBySentAtDesc(
                        tenantId, fromPhone, WaitlistOffer.Status.OFFERED)
                .filter(o -> o.getExpiresAt() == null || o.getExpiresAt().isAfter(now))
                .next();
    }

    /**
     * The atomic slot claim. A {@code findAndModify} on the per-slot claim doc with the
     * {@code claimedByContactId:null} guard: a returned doc = winner (we wrote it); a
     * {@code DuplicateKeyException} = loser (someone else already holds the slot).
     */
    @SuppressWarnings("rawtypes")
    private Mono<ClaimOutcome> claimSlot(UUID tenantId, WaitlistOffer offer) {
        UUID contactId = offer.getContactId();
        String slotKey = tenantId + ":" + offer.getSlotKey();
        Query query = new Query(Criteria.where("_id").is(slotKey)
                .and("claimedByContactId").is(null));
        Update update = new Update()
                .set("claimedByContactId", contactId)
                .set("claimedOfferId", offer.getId())
                .set("tenantId", tenantId)
                .set("slotKey", offer.getSlotKey())
                .set("claimedAt", Instant.now());
        FindAndModifyOptions opts = FindAndModifyOptions.options().returnNew(true).upsert(true);

        return mongo.findAndModify(query, update, opts, Map.class, CLAIMS_COLLECTION)
                .flatMap(doc -> resolveClaimer(doc, tenantId, contactId, offer))
                // A DuplicateKey is NOT automatically a loss. MongoDB's findAndModify+upsert on a unique
                // _id can surface E11000 to BOTH concurrent racers under tight timing (SERVER-14322) — so
                // treating it as an unconditional loss let two simultaneous YES BOTH lose, leaving the
                // freed slot unfilled (won=0; caught by WaitlistEngineIT.concurrentDoubleYes in CI). The
                // claim doc now exists and its claimedByContactId is the single source of truth: re-read
                // it and let whoever's id actually landed win — everyone else loses deterministically.
                .onErrorResume(DuplicateKeyException.class, e ->
                        mongo.findById(slotKey, Map.class, CLAIMS_COLLECTION)
                                .flatMap(doc -> resolveClaimer(doc, tenantId, contactId, offer))
                                .switchIfEmpty(Mono.defer(() -> onLose(tenantId, offer))));
    }

    /**
     * Decide the single outcome from the authoritative claim doc: WON iff its {@code claimedByContactId}
     * is this claimer's id, else LOST. Shared by the findAndModify success path and the DuplicateKey
     * re-read path so both converge on the doc's one true winner (never a double-win, never a double-lose).
     */
    @SuppressWarnings("rawtypes")
    private Mono<ClaimOutcome> resolveClaimer(Map doc, UUID tenantId, UUID contactId, WaitlistOffer offer) {
        Object claimer = doc.get("claimedByContactId");
        if (claimer != null && contactId != null
                && claimer.toString().equals(contactId.toString())) {
            return onWin(tenantId, offer);
        }
        return onLose(tenantId, offer);
    }

    /**
     * Winner path: reconstruct the slot, dispatch to the matching {@link SlotMaterializer} (the consumer's
     * real record, or the no-op), flip the offer CLAIMED, supersede siblings, mark the entry FULFILLED,
     * send the confirmation SMS, emit {@code WAITLIST_ENGINE_SLOT_CLAIMED}. Best-effort on the comms.
     */
    private Mono<ClaimOutcome> onWin(UUID tenantId, WaitlistOffer offer) {
        WaitlistSlot slot = slotFromOffer(offer);
        SlotMaterializer materializer = selectMaterializer(offer.getSlotType());
        return materializer.materialize(tenantId, offer, slot)
                .defaultIfEmpty(SlotMaterializer.MaterializedRef.none())
                .flatMap(ref -> markOfferClaimed(offer)
                        .then(supersedeSiblings(tenantId, offer))
                        .then(markEntryFulfilled(offer))
                        .then(confirmWinner(offer))
                        .doOnSuccess(v -> emitClaimed(tenantId, offer, ref))
                        .thenReturn(ClaimOutcome.WON))
                .onErrorResume(err -> {
                    // A materialize failure (e.g. the consumer's slot got taken another way) must not
                    // corrupt state: leave the offer OFFERED for a possible retry/expiry, log, and report
                    // LOST so the inbound path apologizes rather than silently dropping.
                    log.warn("E4: winner materialize failed for offer {} (slot {}), apologizing: {}",
                            offer.getId(), offer.getSlotKey(), err.toString());
                    return apologize(offer).thenReturn(ClaimOutcome.LOST);
                });
    }

    /** Loser path: apologetic auto-reply, mark this offer SUPERSEDED, no record. */
    private Mono<ClaimOutcome> onLose(UUID tenantId, WaitlistOffer offer) {
        return offers.findById(offer.getId())
                .defaultIfEmpty(offer)
                .flatMap(fresh -> {
                    // Only flip if still OFFERED (don't clobber a CLAIMED winner's own offer).
                    if (fresh.getStatus() == WaitlistOffer.Status.OFFERED) {
                        return offers.save(fresh.toBuilder()
                                .status(WaitlistOffer.Status.SUPERSEDED).build());
                    }
                    return Mono.just(fresh);
                })
                .onErrorResume(e -> Mono.empty()) // a lost status-flip race is fine — still a loser
                .then(apologize(offer))
                .thenReturn(ClaimOutcome.LOST);
    }

    /**
     * Select the materializer: the first registered NON-no-op materializer whose {@code supports(slotType)}
     * is true; else the no-op fallback (the {@code DefaultHandoffIntentHandler} / E2 router precedent).
     */
    private SlotMaterializer selectMaterializer(String slotType) {
        if (slotType != null) {
            for (SlotMaterializer m : materializers) {
                if (NoOpSlotMaterializer.KEY.equals(m.key())) {
                    continue; // the no-op is the fallback, never a first-pass match
                }
                if (m.supports(slotType)) {
                    return m;
                }
            }
        }
        return noOpMaterializer;
    }

    private WaitlistSlot slotFromOffer(WaitlistOffer offer) {
        return WaitlistSlot.builder()
                .slotType(offer.getSlotType())
                .slotKey(offer.getSlotKey())
                .providerId(offer.getProviderId())
                .slotStart(offer.getSlotStart())
                .slotEnd(offer.getSlotEnd())
                .durationMinutes(offer.getDurationMinutes())
                .build();
    }

    private Mono<WaitlistOffer> markOfferClaimed(WaitlistOffer offer) {
        return offers.findById(offer.getId())
                .defaultIfEmpty(offer)
                .flatMap(fresh -> offers.save(fresh.toBuilder()
                        .status(WaitlistOffer.Status.CLAIMED).build()));
    }

    private Mono<Void> supersedeSiblings(UUID tenantId, WaitlistOffer winner) {
        return offers.findByTenantIdAndSlotKey(tenantId, winner.getSlotKey())
                .filter(o -> !o.getId().equals(winner.getId()))
                .filter(o -> o.getStatus() == WaitlistOffer.Status.OFFERED)
                .flatMap(o -> offers.save(o.toBuilder()
                        .status(WaitlistOffer.Status.SUPERSEDED).build()))
                .then();
    }

    private Mono<Void> markEntryFulfilled(WaitlistOffer winner) {
        if (winner.getWaitlistEntryId() == null) return Mono.empty();
        return entries.findById(winner.getWaitlistEntryId())
                .flatMap(e -> entries.save(e.toBuilder()
                        .status(WaitlistEntry.Status.FULFILLED).build()))
                .then()
                .onErrorResume(e -> Mono.empty());
    }

    private Mono<Void> confirmWinner(WaitlistOffer offer) {
        return sendSms(offer.getContactPhone(), confirmationTemplate);
    }

    private Mono<Void> apologize(WaitlistOffer offer) {
        return sendSms(offer.getContactPhone(), apologyTemplate);
    }

    private Mono<Void> sendSms(String phone, String body) {
        if (phone == null || phone.isBlank()) return Mono.empty();
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(new PhoneContact(phone))
                .body(body)
                .build();
        return twilioSms.sendSms(req)
                .onErrorResume(err -> {
                    log.warn("E4: claim SMS send failed for {} (best-effort): {}", phone, err.toString());
                    return Mono.just(false);
                })
                .then();
    }

    private void emitClaimed(UUID tenantId, WaitlistOffer offer, SlotMaterializer.MaterializedRef ref) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("slotKey", offer.getSlotKey());
        payload.put("offerId", offer.getId());
        payload.put("contactId", offer.getContactId());
        payload.put("refType", ref != null ? ref.refType() : null);
        payload.put("refId", ref != null ? ref.refId() : null);
        events.publish(DomainEvent.of(
                DomainEventType.WAITLIST_ENGINE_SLOT_CLAIMED, tenantId,
                ref != null && ref.refId() != null ? ref.refId() : offer.getId(), payload));
    }

    private static String blankTo(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
