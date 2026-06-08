package com.kumouri.kmodigipresbe.module.chairfill.gapfill;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntryRepository;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOfferRepository;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * ChairFill CF-3 — the <strong>double-YES-correct</strong> heart of the gap-fill showpiece. Resolves
 * the most-recent un-expired {@code OFFERED} {@link WaitlistOffer} for an inbound YES and atomically
 * claims the freed <em>slot</em> for the first replier; every later YES for that same slot gets an
 * apologetic auto-reply and creates no booking.
 *
 * <h2>The atomic claim (CF-3 D2 — the one piece that MUST be correct)</h2>
 * The contended resource is "the freed slot," which no single existing document uniquely represents — so
 * we mint a per-slot {@code chairfill_waitlist_claims} doc keyed {@code _id = "<tenantId>:<freedBookingId>"}
 * and claim it with a single conditional {@link ReactiveMongoTemplate#findAndModify} — the
 * {@code WorkOrderNumberGenerator} precedent:
 * <pre>{@code
 *   query  = { _id: slotKey, claimedByContactId: null }   // the unclaimed guard — the race gate
 *   update = { $set: { claimedByContactId: X, claimedOfferId: offerId, claimedAt: now } }
 *   opts   = findAndModify().returnNew(true).upsert(true)
 * }</pre>
 * Mongo single-document updates are atomic, so <strong>exactly one</strong> concurrent YES finds the doc
 * unclaimed and writes it (winner — gets the modified doc back, claimed by itself). For every other
 * concurrent YES the {@code claimedByContactId:null} predicate no longer matches, so {@code upsert:true}
 * attempts to INSERT a second doc with the same unique {@code _id} → {@code DuplicateKeyException} →
 * treated as the loser. (The unique {@code _id} makes the first-ever-claim insert race resolve to one
 * winner + a duplicate-key for the rest.) <strong>First-writer-wins, atomically, at the database.</strong>
 *
 * <p><strong>Winner:</strong> create a real {@link Booking} for the claiming contact in the freed window
 * via the UNCHANGED {@link SalonBookingService#create} (which runs {@code BookingPolicyService.validate},
 * so even a logic slip cannot double-book the stylist; the {@code Booking} {@code @Version} is the further
 * backstop), mark the offer {@code CLAIMED}, mark sibling offers {@code SUPERSEDED}, mark the entry
 * {@code FULFILLED}, send a confirmation SMS, and emit {@code WAITLIST_SLOT_CLAIMED}.
 *
 * <p><strong>Loser(s):</strong> the slot was already claimed by someone else → an apologetic, time-boxed
 * auto-reply ("that slot was just taken — you're first in line for the next"). No booking, no error
 * surfaced. Best-effort throughout (plan HARD GATE 3): a Claude/SMS/booking hiccup never corrupts state.
 */
@Slf4j
public class WaitlistClaimService {

    private static final String CLAIMS_COLLECTION = "chairfill_waitlist_claims";

    private final ReactiveMongoTemplate mongo;
    private final WaitlistOfferRepository offers;
    private final WaitlistEntryRepository entries;
    private final ContactRepository contacts;
    private final SalonBookingService bookingService;
    private final TwilioSmsService twilioSms;
    private final DomainEventPublisher events;

    private final String confirmationTemplate;
    private final String apologyTemplate;

    public WaitlistClaimService(ReactiveMongoTemplate mongo,
                                WaitlistOfferRepository offers,
                                WaitlistEntryRepository entries,
                                ContactRepository contacts,
                                SalonBookingService bookingService,
                                TwilioSmsService twilioSms,
                                DomainEventPublisher events,
                                String confirmationTemplate,
                                String apologyTemplate) {
        this.mongo = mongo;
        this.offers = offers;
        this.entries = entries;
        this.contacts = contacts;
        this.bookingService = bookingService;
        this.twilioSms = twilioSms;
        this.events = events;
        this.confirmationTemplate = blankTo(confirmationTemplate,
                "You're booked! See you soon.");
        this.apologyTemplate = blankTo(apologyTemplate,
                "Sorry — that slot was just taken. You're still first in line for the next opening!");
    }

    /** Outcome of an inbound YES — for the inbound-SMS service to log / branch on. */
    public enum ClaimOutcome { WON, LOST, NO_OPEN_OFFER }

    /**
     * Handle an inbound affirmative ("YES") from {@code fromPhone} for {@code tenantId}: correlate to the
     * most-recent un-expired {@code OFFERED} offer for that phone, then run the atomic claim. Returns the
     * outcome. Establishes the synthetic {@code TenantContext} (the {@code RiskTieredPreventionService}
     * posture) so {@code SalonBookingService.create} + the repos run tenant-scoped.
     */
    public Mono<ClaimOutcome> handleAffirmative(UUID tenantId, String fromPhone) {
        if (fromPhone == null || fromPhone.isBlank()) {
            return Mono.just(ClaimOutcome.NO_OPEN_OFFER);
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return resolveOpenOffer(tenantId, fromPhone)
                .flatMap(offer -> claim(tenantId, offer))
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
    private Mono<ClaimOutcome> claim(UUID tenantId, WaitlistOffer offer) {
        UUID contactId = offer.getContactId();
        String slotKey = tenantId + ":" + offer.getFreedBookingId();
        Query query = new Query(Criteria.where("_id").is(slotKey)
                .and("claimedByContactId").is(null));
        Update update = new Update()
                .set("claimedByContactId", contactId)
                .set("claimedOfferId", offer.getId())
                .set("tenantId", tenantId)
                .set("freedBookingId", offer.getFreedBookingId())
                .set("claimedAt", Instant.now());
        FindAndModifyOptions opts = FindAndModifyOptions.options().returnNew(true).upsert(true);

        return mongo.findAndModify(query, update, opts, Map.class, CLAIMS_COLLECTION)
                .flatMap(doc -> {
                    Object claimer = doc.get("claimedByContactId");
                    // Defensive: with the null-guard query a returned doc is always claimed by us, but
                    // re-check — if somehow claimed by another, treat as lost (never double-book).
                    if (claimer != null && claimer.toString().equals(contactId.toString())) {
                        return onWin(tenantId, offer);
                    }
                    return onLose(offer);
                })
                // upsert tried to insert a duplicate _id because the null-guard didn't match (slot already
                // claimed by someone else) OR a concurrent insert lost the unique-_id race → the loser.
                .onErrorResume(DuplicateKeyException.class, e -> onLose(offer));
    }

    /**
     * Winner path: create the real booking via the unchanged {@link SalonBookingService#create} (policy
     * validated, {@code @Version} backstop), flip the offer CLAIMED, supersede siblings, mark the entry
     * FULFILLED, send the confirmation SMS, emit {@code WAITLIST_SLOT_CLAIMED}. Best-effort on the comms.
     */
    private Mono<ClaimOutcome> onWin(UUID tenantId, WaitlistOffer offer) {
        Booking toCreate = Booking.builder()
                .contactId(offer.getContactId())
                .staffMemberId(offer.getStaffMemberId())
                .serviceMenuItemId(offer.getServiceMenuItemId())
                .serviceMenuItemName(offer.getServiceMenuItemName())
                .scheduledStart(offer.getSlotStart())
                .scheduledEnd(offer.getSlotEnd())
                .build();
        return bookingService.create(toCreate)
                .flatMap(newBooking -> markOfferClaimed(offer)
                        .then(supersedeSiblings(tenantId, offer))
                        .then(markEntryFulfilled(offer))
                        .then(confirmWinner(tenantId, offer))
                        .doOnSuccess(v -> emitClaimed(tenantId, offer, newBooking.getId()))
                        .thenReturn(ClaimOutcome.WON))
                .onErrorResume(err -> {
                    // A booking-create failure (e.g. policy conflict — the stylist got booked another
                    // way) must not corrupt state: leave the offer OFFERED for a possible retry/expiry,
                    // log, and report LOST so the inbound path apologizes rather than silently dropping.
                    log.warn("CF-3: winner booking-create failed for offer {} (slot {}), apologizing: {}",
                            offer.getId(), offer.getFreedBookingId(), err.toString());
                    return apologize(tenantId, offer).thenReturn(ClaimOutcome.LOST);
                });
    }

    /** Loser path: apologetic auto-reply, mark this offer SUPERSEDED, no booking. */
    private Mono<ClaimOutcome> onLose(WaitlistOffer offer) {
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
                .then(apologize(offer.getTenantId() != null ? offer.getTenantId() : null, offer))
                .thenReturn(ClaimOutcome.LOST);
    }

    private Mono<WaitlistOffer> markOfferClaimed(WaitlistOffer offer) {
        return offers.findById(offer.getId())
                .defaultIfEmpty(offer)
                .flatMap(fresh -> offers.save(fresh.toBuilder()
                        .status(WaitlistOffer.Status.CLAIMED).build()));
    }

    private Mono<Void> supersedeSiblings(UUID tenantId, WaitlistOffer winner) {
        return offers.findByTenantIdAndFreedBookingId(tenantId, winner.getFreedBookingId())
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

    private Mono<Void> confirmWinner(UUID tenantId, WaitlistOffer offer) {
        return sendSms(offer.getContactPhone(), confirmationTemplate);
    }

    private Mono<Void> apologize(UUID tenantId, WaitlistOffer offer) {
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
                    log.warn("CF-3: claim SMS send failed for {} (best-effort): {}", phone, err.toString());
                    return Mono.just(false);
                })
                .then();
    }

    private void emitClaimed(UUID tenantId, WaitlistOffer offer, UUID newBookingId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("freedBookingId", offer.getFreedBookingId());
        payload.put("newBookingId", newBookingId);
        payload.put("offerId", offer.getId());
        payload.put("contactId", offer.getContactId());
        events.publish(DomainEvent.of(
                DomainEventType.WAITLIST_SLOT_CLAIMED, tenantId,
                newBookingId != null ? newBookingId : offer.getFreedBookingId(), payload));
    }

    private static String blankTo(String v, String fallback) {
        return (v == null || v.isBlank()) ? fallback : v;
    }
}
