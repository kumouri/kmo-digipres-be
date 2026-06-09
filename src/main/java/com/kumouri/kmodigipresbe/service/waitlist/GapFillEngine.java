package com.kumouri.kmodigipresbe.service.waitlist;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineEntryRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineOfferRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E4 — the vertical-agnostic gap-fill orchestrator. The generic sibling of ChairFill CF-3's
 * {@code GapFillService} (which stays byte-equivalent), with two deliberate divergences (design directive
 * #3):
 * <ol>
 *   <li><strong>Consumer-triggered, not an event subscriber.</strong> There is NO {@code @PostConstruct}
 *       {@code BOOKING_CANCELLED} subscriber here — a consumer (the Health "RescheduleFlow" T7, a salon
 *       adapter, …) calls {@link #gapFill(UUID, WaitlistSlot)} from its own freed-slot trigger. So the
 *       engine is dormant until a consumer invokes it (and module-gated off by default at the bean level).</li>
 *   <li><strong>No AI dependency.</strong> The offer copy is a deterministic generic template (no Anthropic
 *       call), keeping the engine dependency-light + vertical-agnostic. A consumer can layer AI copy later.</li>
 * </ol>
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>A consumer frees a slot → calls {@code gapFill(tenantId, slot)} with the
 *       {@link WaitlistSlot}.</li>
 *   <li>{@link WaitlistRankingService} ranks OPEN, opted-in, slot-matching {@link WaitlistEntry}s by
 *       inverted show-risk — most-likely-to-show first.</li>
 *   <li>For the top-N ({@code kmosf.waitlist.max-offers}), a {@link WaitlistOffer} row is minted
 *       ({@code OFFERED}, with {@code expiresAt}); a time-boxed offer SMS is sent;
 *       {@code WAITLIST_ENGINE_OFFER_SENT} is emitted.</li>
 * </ol>
 * The first inbound YES then claims the slot via {@link WaitlistClaimEngine} (the atomic gate).
 *
 * <h2>Consent (TCPA)</h2>
 * Only OPEN + {@code smsOptIn} entries are candidates, and a contact carrying the
 * {@value RiskTieredPreventionService#SMS_OPT_OUT_TAG} tag (honors STOP — the same single source of truth
 * the E2 responder engine reuses) is dropped before any offer.
 *
 * <h2>Best-effort + blast-radius-zero</h2>
 * Every external step (SMS) is wrapped {@code onErrorResume}: a failure degrades (skip that one offer) and
 * never corrupts the consumer's freed slot or the waitlist. {@link #gapFill} additionally re-checks
 * {@code Tenant.enabledModules} membership (defense-in-depth) so a call for a tenant that has not enabled
 * the {@code waitlist} module is a hard no-op.
 */
@Slf4j
public class GapFillEngine {

    /** The module key membership-checked for defense-in-depth (mirrors {@code WaitlistAutoConfiguration.MODULE_KEY}). */
    public static final String MODULE_KEY = "waitlist";

    private final DomainEventPublisher events;
    private final TenantRepository tenants;
    private final WaitlistEngineEntryRepository entries;
    private final WaitlistEngineOfferRepository offers;
    private final ContactRepository contacts;
    private final WaitlistRankingService rankingService;
    private final TwilioSmsService twilioSms;

    private final int maxOffers;
    private final long offerTtlMinutes;

    public GapFillEngine(DomainEventPublisher events,
                         TenantRepository tenants,
                         WaitlistEngineEntryRepository entries,
                         WaitlistEngineOfferRepository offers,
                         ContactRepository contacts,
                         WaitlistRankingService rankingService,
                         TwilioSmsService twilioSms,
                         int maxOffers,
                         long offerTtlMinutes) {
        this.events = events;
        this.tenants = tenants;
        this.entries = entries;
        this.offers = offers;
        this.contacts = contacts;
        this.rankingService = rankingService;
        this.twilioSms = twilioSms;
        this.maxOffers = maxOffers;
        this.offerTtlMinutes = offerTtlMinutes;
    }

    /**
     * Gap-fill a freed slot: rank the waitlist, issue time-boxed offers to the top-N, send each an SMS.
     * Returns the number of offers actually sent. Establishes a synthetic {@code TenantContext} so the
     * repos + SMS run tenant-scoped (the engine is consumer-triggered from outside a request context).
     */
    public Mono<Integer> gapFill(UUID tenantId, WaitlistSlot slot) {
        if (tenantId == null || slot == null || slot.slotKey() == null || slot.slotKey().isBlank()) {
            return Mono.just(0);
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return process(tenantId, slot)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Integer> process(UUID tenantId, WaitlistSlot slot) {
        // Defense-in-depth: a gap-fill call for a tenant that has not enabled the waitlist module is a
        // hard no-op (the CF-3 GapFillService.process posture).
        return tenants.findById(tenantId)
                .filter(t -> t.getEnabledModules() != null && t.getEnabledModules().contains(MODULE_KEY))
                .flatMap(t -> runGapFill(tenantId, slot))
                .defaultIfEmpty(0);
    }

    private Mono<Integer> runGapFill(UUID tenantId, WaitlistSlot slot) {
        return entries.findByTenantIdAndStatus(tenantId, WaitlistEntry.Status.OPEN).collectList()
                .flatMap(open -> rankingService.rank(open, slot)
                        .flatMap(ranked -> {
                            if (ranked.isEmpty()) {
                                log.debug("E4: no matching waitlist entries for freed slot {}", slot.slotKey());
                                return Mono.just(0);
                            }
                            List<WaitlistRankingService.RankedEntry> top = ranked.size() > maxOffers
                                    ? ranked.subList(0, maxOffers) : ranked;
                            return sendOffers(tenantId, slot, top);
                        }));
    }

    private Mono<Integer> sendOffers(UUID tenantId, WaitlistSlot slot,
                                     List<WaitlistRankingService.RankedEntry> top) {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(offerTtlMinutes));
        int[] rankCounter = {0};
        return Flux.fromIterable(top)
                .concatMap(re -> {
                    int rank = rankCounter[0]++;
                    return sendOneOffer(tenantId, slot, expiresAt, re.entry(), rank)
                            .onErrorResume(err -> {
                                log.warn("E4: failed to send offer to entry {} for slot {} "
                                                + "(best-effort, skipping): {}", re.entry().getId(),
                                        slot.slotKey(), err.toString());
                                return Mono.just(false);
                            });
                })
                .filter(Boolean::booleanValue)
                .count()
                .map(Long::intValue);
    }

    private Mono<Boolean> sendOneOffer(UUID tenantId, WaitlistSlot slot, Instant expiresAt,
                                       WaitlistEntry entry, int rank) {
        UUID contactId = entry.getContactId();
        if (contactId == null) {
            return Mono.just(false);
        }
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .flatMap(contact -> {
                    if (hasOptedOut(contact)) {
                        log.debug("E4: contact {} opted out of SMS — skipping offer for slot {}",
                                contactId, slot.slotKey());
                        return Mono.just(false);
                    }
                    String phone = firstPhone(contact);
                    if (phone == null) {
                        return Mono.just(false);
                    }
                    String text = offerText(contact.getFirstName(), slot, expiresAt);
                    return mintAndSend(tenantId, slot, expiresAt, entry, contact, phone, rank, text);
                })
                .defaultIfEmpty(false);
    }

    private Mono<Boolean> mintAndSend(UUID tenantId, WaitlistSlot slot, Instant expiresAt,
                                      WaitlistEntry entry, Contact contact, String phone, int rank,
                                      String text) {
        WaitlistOffer offer = WaitlistOffer.builder()
                .slotKey(slot.slotKey())
                .slotType(slot.slotType())
                .waitlistEntryId(entry.getId())
                .contactId(contact.getId())
                .contactPhone(phone)
                .providerId(slot.providerId())
                .slotStart(slot.slotStart())
                .slotEnd(slot.slotEnd())
                .durationMinutes(slot.durationMinutes())
                .rank(rank)
                .status(WaitlistOffer.Status.OFFERED)
                .sentAt(Instant.now())
                .expiresAt(expiresAt)
                .build();
        return offers.save(offer)
                .flatMap(saved -> sendSms(phone, text)
                        .doOnSuccess(v -> emitOfferSent(tenantId, saved))
                        .thenReturn(true));
    }

    private Mono<Void> sendSms(String phone, String body) {
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(new PhoneContact(phone))
                .body(body)
                .build();
        return twilioSms.sendSms(req)
                .onErrorResume(err -> {
                    log.warn("E4: offer SMS send failed for {} (best-effort): {}", phone, err.toString());
                    return Mono.just(false);
                })
                .then();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean hasOptedOut(Contact contact) {
        Set<String> tags = contact.getTags();
        return tags != null && tags.contains(RiskTieredPreventionService.SMS_OPT_OUT_TAG);
    }

    private String firstPhone(Contact contact) {
        List<PhoneNumber> phones = contact.getPhones();
        if (phones == null) return null;
        for (PhoneNumber p : phones) {
            if (p != null && p.number() != null && !p.number().isBlank()) {
                return p.number();
            }
        }
        return null;
    }

    private static final DateTimeFormatter WHEN_FMT =
            DateTimeFormatter.ofPattern("EEEE 'at' h:mm a", Locale.US);

    private String humanWhen(Instant when) {
        if (when == null) return null;
        return WHEN_FMT.format(when.atZone(ZoneId.of("UTC")));
    }

    /**
     * The deterministic, vertical-agnostic offer copy (no AI — design directive #3). Mentions the freed
     * window + the YES time-box; STOP-aware. A consumer wanting richer copy can layer it on, but the
     * engine never depends on an AI call.
     */
    private String offerText(String firstName, WaitlistSlot slot, Instant expiresAt) {
        long minutes = Math.max(1, Duration.between(Instant.now(), expiresAt).toMinutes());
        String replyWindow = "the next " + minutes + " minutes";
        String when = humanWhen(slot.slotStart());
        StringBuilder sb = new StringBuilder("Hi");
        if (firstName != null && !firstName.isBlank()) sb.append(' ').append(firstName.trim());
        sb.append("! A spot just opened");
        if (when != null && !when.isBlank()) sb.append(' ').append(when);
        sb.append(". Want it? Reply YES in ").append(replyWindow)
                .append(" and it's yours. Reply STOP to opt out.");
        return sb.toString();
    }

    private void emitOfferSent(UUID tenantId, WaitlistOffer offer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("slotKey", offer.getSlotKey());
        payload.put("offerId", offer.getId());
        payload.put("contactId", offer.getContactId());
        payload.put("rank", offer.getRank());
        payload.put("expiresAt", offer.getExpiresAt());
        events.publish(DomainEvent.of(
                DomainEventType.WAITLIST_ENGINE_OFFER_SENT, tenantId, offer.getId(), payload));
    }
}
