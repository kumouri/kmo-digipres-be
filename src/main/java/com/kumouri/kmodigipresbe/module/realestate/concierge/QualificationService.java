package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.module.realestate.model.BuyerQualification;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-2) — accumulates the buyer's {@link BuyerQualification} onto the
 * {@link ConciergeConversation} and, on enough signal, materializes the buyer {@code Contact} + a
 * concierge-sourced {@code Deal} the unchanged nightly {@code LeadScoringV2Service} then tiers
 * (RE-2 §5 / decision 3).
 *
 * <p><strong>The scoring-reuse contract.</strong> RE-2 adds NO new ML and does NOT touch the scorer. The
 * materialized Deal carries {@code customFields.source = "concierge"} + {@code listingId} so that (a) the
 * existing nightly {@code LeadScoringV2Service} tiers the buyer Contact off its Deals exactly as it does
 * for every CRM contact, and (b) the {@link LeadHandoffService} {@code LEAD_SCORE_UPDATED} subscriber can
 * recognise a concierge-sourced realestate Deal for the hot-handoff (and no-op for everything else).
 *
 * <p><strong>Find-or-create, accumulate, never clobber.</strong> The buyer Contact is found-or-created by
 * the {@code buyerPhone} (the {@code TwilioVoicemailService} find-or-create-by-phone precedent — an
 * existing staff-curated contact is reused untouched). The Deal is found-or-created per
 * {@code (buyerPhone × listing)} (keyed via the conversation's {@code dealId}). Qualification fields
 * <em>accumulate</em> ({@link #merge}) — a later turn never clears an earlier non-null value (a buyer who
 * mentions budget once and timeline later ends with both). The Deal is updated in place on subsequent
 * turns (value ← latest budget, customFields ← latest qualification).
 *
 * <p><strong>Best-effort &amp; idempotent (RE-2 HARD GATE 3).</strong> The materialization runs only when
 * there is a budget signal (the "enough signal" gate); a blank extraction is a clean no-op that still
 * persists the (possibly partial) accumulated qualification. The {@code Deal} write reuses the conversation
 * {@code dealId} so re-entry updates the same Deal (no duplicate). All under the caller's synthetic tenant
 * context. Advisory {@code 4261} on a deal-materialization conflict (logged, never thrown).
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateAutoConfiguration} so it exists only when the
 * module is enabled. Reuses {@code Deal}/{@code Contact} repositories directly (the synthetic-context
 * inbound path passes tenant explicitly), {@code TwilioVoicemailService}-style.
 */
@Slf4j
public class QualificationService {

    /** The Deal customField marker the hot-handoff keys off (a concierge-sourced realestate Deal). */
    public static final String DEAL_SOURCE_KEY = "source";
    public static final String DEAL_SOURCE_CONCIERGE = "concierge";
    /** The Deal customField carrying the originating listing id (string). */
    public static final String DEAL_LISTING_ID_KEY = "listingId";

    private final ContactRepository contacts;
    private final DealRepository deals;
    private final DomainEventPublisher events;

    public QualificationService(ContactRepository contacts,
                                DealRepository deals,
                                DomainEventPublisher events) {
        this.contacts = contacts;
        this.deals = deals;
        this.events = events;
    }

    /**
     * Merges {@code extracted} into the conversation's accumulated qualification, then — if there is now
     * enough signal (a budget) — find-or-creates the buyer Contact + the concierge Deal and links them onto
     * the conversation. Returns the (possibly mutated) conversation; the caller persists it.
     *
     * <p>Runs under the caller's tenant context. Best-effort: a materialization failure logs {@code 4261}
     * and returns the conversation with the accumulated qualification intact (the conversation is never
     * dropped).
     */
    public Mono<ConciergeConversation> qualify(UUID tenantId, Listing listing,
                                               ConciergeConversation conv, BuyerQualification extracted) {
        BuyerQualification merged = merge(conv.getQualification(), extracted);
        conv.setQualification(merged);

        if (!hasEnoughSignal(merged)) {
            // Nothing material yet — keep the accumulated (partial) qualification; no Deal/Contact write.
            return Mono.just(conv);
        }
        return materialize(tenantId, listing, conv, merged)
                .onErrorResume(err -> {
                    log.warn("RE-2 qualification: Deal materialization failed (best-effort, advisory 4261) "
                            + "for conversation {}: {}", conv.getId(), err.toString());
                    return Mono.just(conv);
                });
    }

    /**
     * Accumulating merge — each field takes the freshly-extracted value when present, else keeps the prior
     * value (a later turn never clears an earlier non-null). {@code dealMaterialized} is sticky-true.
     */
    static BuyerQualification merge(BuyerQualification prior, BuyerQualification fresh) {
        BuyerQualification base = prior != null ? prior : new BuyerQualification();
        BuyerQualification add = fresh != null ? fresh : new BuyerQualification();
        return BuyerQualification.builder()
                .budget(add.getBudget() != null ? add.getBudget() : base.getBudget())
                .timeline(add.getTimeline() != null ? add.getTimeline() : base.getTimeline())
                .financing(add.getFinancing() != null ? add.getFinancing() : base.getFinancing())
                .preApproved(add.getPreApproved() != null ? add.getPreApproved() : base.getPreApproved())
                .intent(add.getIntent() != null ? add.getIntent() : base.getIntent())
                .dealMaterialized(base.isDealMaterialized())
                .build();
    }

    /** "Enough signal" to create/update a Deal = a budget (the value the pipeline + scorer key on). */
    private static boolean hasEnoughSignal(BuyerQualification q) {
        return q != null && q.getBudget() != null;
    }

    private Mono<ConciergeConversation> materialize(UUID tenantId, Listing listing,
                                                    ConciergeConversation conv, BuyerQualification q) {
        return resolveContact(tenantId, conv)
                .flatMap(contact -> upsertDeal(tenantId, listing, conv, contact, q)
                        .map(deal -> {
                            conv.setContactId(contact.getId());
                            conv.setDealId(deal.getId());
                            q.setDealMaterialized(true);
                            conv.setQualification(q);
                            events.publish(DomainEvent.of(
                                    DomainEventType.CONCIERGE_LEAD_QUALIFIED, tenantId, conv.getId(),
                                    payload(conv, contact, deal, listing)));
                            return conv;
                        }));
    }

    /**
     * Find-or-create the buyer Contact by phone (the {@code TwilioVoicemailService} precedent) — if the
     * conversation already linked a contact, reuse it; else look up by {@code buyerPhone}; else create one.
     * An existing contact is returned untouched (we don't rewrite staff-curated data from an inbound text).
     */
    private Mono<Contact> resolveContact(UUID tenantId, ConciergeConversation conv) {
        if (conv.getContactId() != null) {
            return contacts.findByTenantIdAndId(tenantId, conv.getContactId())
                    .switchIfEmpty(Mono.defer(() -> contacts.save(buildContact(tenantId, conv.getBuyerPhone()))));
        }
        String phone = conv.getBuyerPhone();
        if (phone == null || phone.isBlank()) {
            return contacts.save(buildContact(tenantId, null));
        }
        return contacts.findByTenantAndPhoneNumber(tenantId, phone)
                .next()
                .switchIfEmpty(Mono.defer(() -> contacts.save(buildContact(tenantId, phone))));
    }

    private Contact buildContact(UUID tenantId, String phone) {
        List<PhoneNumber> phones = new ArrayList<>();
        if (phone != null && !phone.isBlank()) {
            phones.add(PhoneNumber.builder().number(phone).label("concierge").build());
        }
        String displayName = phone != null ? "Listing buyer " + phone : "Listing buyer";
        return Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName(displayName)
                .phones(phones)
                .tags(Set.of("realestate-buyer"))
                .build();
    }

    /**
     * Find-or-create the concierge Deal for this (buyer × listing). Reuses the conversation's linked
     * {@code dealId} when present (idempotent re-entry → updates the same Deal); else creates a NEW-stage
     * Deal with {@code value}=budget, {@code primaryContactId}=buyer, and the concierge/listing markers in
     * {@code customFields}. The agent (owner-user) routing is taken from the listing when present.
     */
    private Mono<Deal> upsertDeal(UUID tenantId, Listing listing, ConciergeConversation conv,
                                  Contact contact, BuyerQualification q) {
        if (conv.getDealId() != null) {
            return deals.findByTenantIdAndId(tenantId, conv.getDealId())
                    .flatMap(existing -> deals.save(applyQualification(existing, q)))
                    .switchIfEmpty(Mono.defer(() -> deals.save(buildDeal(tenantId, listing, contact, q))));
        }
        return deals.save(buildDeal(tenantId, listing, contact, q));
    }

    private Deal buildDeal(UUID tenantId, Listing listing, Contact contact, BuyerQualification q) {
        Deal deal = Deal.builder()
                .tenantId(tenantId)
                .title(dealTitle(listing, q))
                .stage(PipelineStage.NEW)
                .value(q.getBudget())
                .primaryContactId(contact.getId())
                .ownerId(listing != null ? listing.getOwnerUserId() : null)
                .build();
        deal.setCustomFields(buildCustomFields(listing, q));
        return deal;
    }

    /** Patches an existing Deal in place with the latest accumulated qualification (value + customFields). */
    private Deal applyQualification(Deal existing, BuyerQualification q) {
        if (q.getBudget() != null) {
            existing.setValue(q.getBudget());
        }
        Map<String, Object> cf = existing.getCustomFields() == null
                ? new HashMap<>() : new HashMap<>(existing.getCustomFields());
        cf.putAll(buildCustomFields(null, q));
        // never drop the source/listing markers an earlier turn stamped
        cf.putIfAbsent(DEAL_SOURCE_KEY, DEAL_SOURCE_CONCIERGE);
        existing.setCustomFields(cf);
        return existing;
    }

    private Map<String, Object> buildCustomFields(Listing listing, BuyerQualification q) {
        Map<String, Object> cf = new HashMap<>();
        cf.put(DEAL_SOURCE_KEY, DEAL_SOURCE_CONCIERGE);
        if (listing != null) {
            cf.put(DEAL_LISTING_ID_KEY, listing.getId().toString());
        }
        if (q.getTimeline() != null) {
            cf.put("timeline", q.getTimeline());
        }
        if (q.getFinancing() != null) {
            cf.put("financing", q.getFinancing());
        }
        if (q.getPreApproved() != null) {
            cf.put("preApproved", q.getPreApproved());
        }
        if (q.getIntent() != null) {
            cf.put("intent", q.getIntent().name());
        }
        return cf;
    }

    private static String dealTitle(Listing listing, BuyerQualification q) {
        String addr = listing != null && listing.getAddressLine() != null
                ? listing.getAddressLine() : "listing";
        String verb = q.getIntent() == BuyerQualification.Intent.SELL ? "Seller" : "Buyer";
        return verb + " inquiry — " + addr;
    }

    private static Map<String, Object> payload(ConciergeConversation conv, Contact contact,
                                               Deal deal, Listing listing) {
        Map<String, Object> p = new HashMap<>();
        p.put("conversationId", conv.getId() == null ? null : conv.getId().toString());
        p.put("contactId", contact.getId() == null ? null : contact.getId().toString());
        p.put("dealId", deal.getId() == null ? null : deal.getId().toString());
        if (listing != null && listing.getId() != null) {
            p.put("listingId", listing.getId().toString());
        }
        if (deal.getValue() != null) {
            p.put("value", deal.getValue());
        }
        return p;
    }

    /** Whether a Deal's customFields mark it concierge-sourced (the hot-handoff recognition key). */
    public static boolean isConciergeSourced(Deal deal) {
        if (deal == null || deal.getCustomFields() == null) {
            return false;
        }
        Object src = deal.getCustomFields().get(DEAL_SOURCE_KEY);
        return DEAL_SOURCE_CONCIERGE.equals(src);
    }

    /** The originating listing id stamped on a concierge Deal's customFields, or null. */
    public static UUID conciergeListingId(Deal deal) {
        if (deal == null || deal.getCustomFields() == null) {
            return null;
        }
        Object v = deal.getCustomFields().get(DEAL_LISTING_ID_KEY);
        if (v == null) {
            return null;
        }
        try {
            return UUID.fromString(v.toString());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
