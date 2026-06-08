package com.kumouri.kmodigipresbe.module.chairfill.gapfill;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.ai.OfferCopyService;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntryRepository;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOfferRepository;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

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
 * ChairFill CF-3 — the gap-fill orchestrator. A dedicated {@code @PostConstruct} subscriber on
 * {@link DomainEventType#BOOKING_CANCELLED} (the additive {@code SalonBookingService.cancel()} emit),
 * mirroring {@link RiskTieredPreventionService} / {@code RebookingNudgeService}'s
 * {@code events.stream().filter(type).flatMap(handle)} pattern + synthetic {@link TenantContext}.
 *
 * <h2>Flow (plan CF-3)</h2>
 * <ol>
 *   <li>A salon booking cancels → {@code BOOKING_CANCELLED} carries the freed slot
 *       ({@code staffMemberId}, {@code serviceMenuItemId}, {@code scheduledStart/End}).</li>
 *   <li>{@link WaitlistMatchService} ranks OPEN, opted-in, slot-matching {@link WaitlistEntry}s by the
 *       CF-1 no-show model <em>inverted</em> — most-likely-to-show first.</li>
 *   <li>For the top-N (config {@code kmosf.chairfill.gapfill.max-offers}), {@link OfferCopyService} (Claude,
 *       budget-gated, best-effort → generic fallback) drafts a personalized, time-boxed offer; a
 *       {@link WaitlistOffer} row is minted ({@code OFFERED}, with {@code expiresAt}); the SMS is sent;
 *       {@code WAITLIST_OFFER_SENT} is emitted.</li>
 * </ol>
 * The first inbound YES then claims the slot via {@link WaitlistClaimService} (the atomic gate).
 *
 * <h2>Consent (plan §4 TCPA)</h2>
 * Only OPEN + {@code smsOptIn} entries are candidates, and a contact carrying the CF-2
 * {@value RiskTieredPreventionService#SMS_OPT_OUT_TAG} tag (honors STOP) is dropped before any offer.
 *
 * <h2>Best-effort + blast-radius-zero (plan HARD GATES 3, 4)</h2>
 * Every external step (Claude, SMS) is wrapped {@code onErrorResume}: a failure degrades (generic copy /
 * skip that one offer) and NEVER corrupts the cancel or the waitlist. Wired as a {@code @Bean} in
 * {@code ChairFillAutoConfiguration} ({@code @ConditionalOnProperty} + {@code @ConditionalOnBean}), so
 * the bean is absent for non-chairfill servers; {@link #process} additionally re-checks
 * {@code Tenant.enabledModules} membership (defense-in-depth) so a {@code BOOKING_CANCELLED} from a
 * non-chairfill salon tenant is a hard no-op (no waitlist subscriber runs there).
 */
@Slf4j
public class GapFillService {

    private final DomainEventPublisher events;
    private final TenantRepository tenants;
    private final WaitlistEntryRepository entries;
    private final WaitlistOfferRepository offers;
    private final ContactRepository contacts;
    private final StaffMemberRepository staff;
    private final ServiceMenuRepository menus;
    private final WaitlistMatchService matchService;
    private final OfferCopyService offerCopy;
    private final TwilioSmsService twilioSms;

    private final int maxOffers;
    private final long offerTtlMinutes;
    private final String brandTone;

    public GapFillService(DomainEventPublisher events,
                          TenantRepository tenants,
                          WaitlistEntryRepository entries,
                          WaitlistOfferRepository offers,
                          ContactRepository contacts,
                          StaffMemberRepository staff,
                          ServiceMenuRepository menus,
                          WaitlistMatchService matchService,
                          OfferCopyService offerCopy,
                          TwilioSmsService twilioSms,
                          int maxOffers,
                          long offerTtlMinutes,
                          String brandTone) {
        this.events = events;
        this.tenants = tenants;
        this.entries = entries;
        this.offers = offers;
        this.contacts = contacts;
        this.staff = staff;
        this.menus = menus;
        this.matchService = matchService;
        this.offerCopy = offerCopy;
        this.twilioSms = twilioSms;
        this.maxOffers = maxOffers;
        this.offerTtlMinutes = offerTtlMinutes;
        this.brandTone = brandTone == null ? "" : brandTone;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.BOOKING_CANCELLED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("GapFillService: error processing BOOKING_CANCELLED for tenant {}",
                                    e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code BOOKING_CANCELLED} end-to-end and complete when
     * the offers are sent (so an IT can drive it deterministically, the {@code RiskTieredPreventionService}
     * posture). Returns the number of offers sent.
     */
    public Mono<Integer> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> p = event.payload();
        UUID freedBookingId = asUuid(p.get("bookingId"));
        if (freedBookingId == null) {
            return Mono.just(0);
        }
        UUID staffMemberId = asUuid(p.get("staffMemberId"));
        String serviceMenuItemId = asString(p.get("serviceMenuItemId"));
        Instant slotStart = asInstant(p.get("scheduledStart"));
        Instant slotEnd = asInstant(p.get("scheduledEnd"));
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return process(tenantId, freedBookingId, staffMemberId, serviceMenuItemId, slotStart, slotEnd)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Integer> process(UUID tenantId, UUID freedBookingId, UUID staffMemberId,
                                  String serviceMenuItemId, Instant slotStart, Instant slotEnd) {
        // Defense-in-depth (HARD GATE 4): a BOOKING_CANCELLED from a non-chairfill salon tenant is a
        // hard no-op. The only real emitter is salon-core cancel(), which fires for every salon tenant;
        // the gap-fill must only run for tenants that have enabled ChairFill.
        return tenants.findById(tenantId)
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(ChairFillAutoConfiguration.MODULE_KEY))
                .flatMap(t -> runGapFill(tenantId, freedBookingId, staffMemberId, serviceMenuItemId,
                        slotStart, slotEnd))
                .defaultIfEmpty(0);
    }

    private Mono<Integer> runGapFill(UUID tenantId, UUID freedBookingId, UUID staffMemberId,
                                     String serviceMenuItemId, Instant slotStart, Instant slotEnd) {
        return entries.findByTenantIdAndStatus(tenantId, WaitlistEntry.Status.OPEN).collectList()
                .flatMap(open -> matchService.rank(tenantId, open, serviceMenuItemId, staffMemberId, slotStart)
                        .flatMap(ranked -> {
                            if (ranked.isEmpty()) {
                                log.debug("CF-3: no matching waitlist entries for freed slot {}", freedBookingId);
                                return Mono.just(0);
                            }
                            List<WaitlistMatchService.RankedEntry> top = ranked.size() > maxOffers
                                    ? ranked.subList(0, maxOffers) : ranked;
                            return resolveServiceName(tenantId, serviceMenuItemId)
                                    .defaultIfEmpty("")
                                    .flatMap(serviceName -> resolveStylistName(staffMemberId)
                                            .defaultIfEmpty("")
                                            .flatMap(stylistName -> sendOffers(tenantId, freedBookingId,
                                                    staffMemberId, serviceMenuItemId, serviceName,
                                                    slotStart, slotEnd, stylistName, top)));
                        }));
    }

    private Mono<Integer> sendOffers(UUID tenantId, UUID freedBookingId, UUID staffMemberId,
                                     String serviceMenuItemId, String serviceName,
                                     Instant slotStart, Instant slotEnd, String stylistName,
                                     List<WaitlistMatchService.RankedEntry> top) {
        Instant expiresAt = Instant.now().plus(Duration.ofMinutes(offerTtlMinutes));
        int[] rankCounter = {0};
        return Flux.fromIterable(top)
                .concatMap(re -> {
                    int rank = rankCounter[0]++;
                    return sendOneOffer(tenantId, freedBookingId, staffMemberId, serviceMenuItemId,
                            serviceName, slotStart, slotEnd, stylistName, expiresAt, re.entry(), rank)
                            .onErrorResume(err -> {
                                log.warn("CF-3: failed to send offer to entry {} for slot {} "
                                        + "(best-effort, skipping): {}", re.entry().getId(),
                                        freedBookingId, err.toString());
                                return Mono.just(false);
                            });
                })
                .filter(Boolean::booleanValue)
                .count()
                .map(Long::intValue);
    }

    private Mono<Boolean> sendOneOffer(UUID tenantId, UUID freedBookingId, UUID staffMemberId,
                                       String serviceMenuItemId, String serviceName,
                                       Instant slotStart, Instant slotEnd, String stylistName,
                                       Instant expiresAt, WaitlistEntry entry, int rank) {
        UUID contactId = entry.getContactId();
        if (contactId == null) {
            return Mono.just(false);
        }
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .flatMap(contact -> {
                    if (hasOptedOut(contact)) {
                        log.debug("CF-3: contact {} opted out of SMS — skipping offer for slot {}",
                                contactId, freedBookingId);
                        return Mono.just(false);
                    }
                    String phone = firstPhone(contact);
                    if (phone == null) {
                        return Mono.just(false);
                    }
                    String replyWindow = "the next " + offerTtlMinutes + " minutes";
                    String when = humanWhen(slotStart);
                    OfferCopyService.OfferContext oc = new OfferCopyService.OfferContext(
                            contact.getFirstName(), emptyToNull(stylistName), emptyToNull(serviceName),
                            when, replyWindow, brandTone);
                    return offerCopy.draftOffer(oc)
                            .onErrorResume(err -> {
                                log.info("CF-3: offer personalization unavailable for slot {} "
                                        + "(falling back to generic): {}", freedBookingId, err.toString());
                                return Mono.just(genericOffer(contact.getFirstName(),
                                        emptyToNull(stylistName), when, replyWindow));
                            })
                            .flatMap(text -> mintAndSend(tenantId, freedBookingId, staffMemberId,
                                    serviceMenuItemId, serviceName, slotStart, slotEnd, expiresAt,
                                    entry, contact, phone, rank, text));
                })
                .defaultIfEmpty(false);
    }

    private Mono<Boolean> mintAndSend(UUID tenantId, UUID freedBookingId, UUID staffMemberId,
                                      String serviceMenuItemId, String serviceName,
                                      Instant slotStart, Instant slotEnd, Instant expiresAt,
                                      WaitlistEntry entry, Contact contact, String phone, int rank,
                                      String text) {
        WaitlistOffer offer = WaitlistOffer.builder()
                .freedBookingId(freedBookingId)
                .waitlistEntryId(entry.getId())
                .contactId(contact.getId())
                .contactPhone(phone)
                .staffMemberId(staffMemberId)
                .serviceMenuItemId(serviceMenuItemId)
                .serviceMenuItemName(emptyToNull(serviceName))
                .slotStart(slotStart)
                .slotEnd(slotEnd)
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
                    log.warn("CF-3: offer SMS send failed for {} (best-effort): {}", phone, err.toString());
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

    private Mono<String> resolveServiceName(UUID tenantId, String serviceMenuItemId) {
        if (serviceMenuItemId == null) return Mono.empty();
        return menus.findAllByTenantId(tenantId)
                .flatMapIterable(m -> m.getServices() == null ? List.<ServiceMenuItem>of() : m.getServices())
                .filter(i -> serviceMenuItemId.equals(i.getId()))
                .next()
                .map(ServiceMenuItem::getName)
                .filter(n -> n != null && !n.isBlank());
    }

    private Mono<String> resolveStylistName(UUID staffMemberId) {
        if (staffMemberId == null) return Mono.empty();
        return staff.findById(staffMemberId)
                .map(StaffMember::getDisplayName)
                .filter(n -> n != null && !n.isBlank());
    }

    private static final DateTimeFormatter WHEN_FMT =
            DateTimeFormatter.ofPattern("EEEE 'at' h:mm a", Locale.US);

    private String humanWhen(Instant when) {
        if (when == null) return null;
        return WHEN_FMT.format(when.atZone(ZoneId.of("UTC")));
    }

    /** Deterministic fallback when Claude is unavailable — never blocks the offer. */
    private String genericOffer(String firstName, String stylistName, String when, String replyWindow) {
        StringBuilder sb = new StringBuilder("Hi");
        if (firstName != null && !firstName.isBlank()) sb.append(' ').append(firstName.trim());
        sb.append("! A spot just opened");
        if (stylistName != null && !stylistName.isBlank()) sb.append(" with ").append(stylistName.trim());
        if (when != null && !when.isBlank()) sb.append(' ').append(when);
        sb.append(". Want it? Reply YES in ").append(replyWindow).append(" and it's yours. Reply STOP to opt out.");
        return sb.toString();
    }

    private void emitOfferSent(UUID tenantId, WaitlistOffer offer) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", offer.getFreedBookingId());
        payload.put("offerId", offer.getId());
        payload.put("contactId", offer.getContactId());
        payload.put("rank", offer.getRank());
        payload.put("expiresAt", offer.getExpiresAt());
        events.publish(DomainEvent.of(
                DomainEventType.WAITLIST_OFFER_SENT, tenantId, offer.getId(), payload));
    }

    private static String emptyToNull(String v) {
        return (v == null || v.isBlank()) ? null : v;
    }

    private static UUID asUuid(Object raw) {
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String asString(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private static Instant asInstant(Object raw) {
        if (raw instanceof Instant i) return i;
        if (raw instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
