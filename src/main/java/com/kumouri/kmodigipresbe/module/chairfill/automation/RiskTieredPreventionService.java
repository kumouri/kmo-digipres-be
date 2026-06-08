package com.kumouri.kmodigipresbe.module.chairfill.automation;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.ai.ReminderCopyService;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.model.BookingStatus;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * ChairFill CF-2 — risk-tiered no-show <strong>prevention</strong>. A dedicated {@code @PostConstruct}
 * subscriber on {@link DomainEventType#BOOKING_RISK_SCORED} (the CF-1 stamp), mirroring
 * {@link com.kumouri.kmodigipresbe.module.salonspa.service.RebookingNudgeService}'s
 * {@code events.stream().filter(type).flatMap(handle)} pattern + synthetic {@link TenantContext}.
 *
 * <p><strong>Why a dedicated subscriber and not a seeded {@code SEND_SMS} WorkflowRule (plan D5):</strong>
 * the generic {@code RuleAction.SEND_SMS} resolves a <em>static</em> template (no per-contact Claude
 * personalization) and there is no {@code REQUIRE_DEPOSIT} action type. The personalized-reminder +
 * deposit-require logic therefore lives here; an owner-tunable baseline LOW-tier reminder rule is still
 * seeded by {@link ChairFillReminderAutomation} so the owner sees a toggle in the admin.
 *
 * <h2>Branch (plan CF-2)</h2>
 * <ul>
 *   <li><strong>HIGH</strong> → {@link SalonBookingService#requireDepositNow} (flip
 *       {@code depositRequired} + mint a DRAFT deposit invoice via the reused salon deposit path) +
 *       send an <em>extra confirmation</em> SMS (Claude-personalized, ask them to reply to confirm).</li>
 *   <li><strong>LOW / MEDIUM</strong> → a single Claude-personalized <em>light reminder</em> SMS.</li>
 * </ul>
 *
 * <h2>TCPA / consent (plan §4 risk)</h2>
 * Before any send: the contact must have a phone and must NOT carry the {@value #SMS_OPT_OUT_TAG} tag
 * (honors STOP — an inbound STOP handler / staff toggle sets this tag); and a <strong>per-contact
 * rolling frequency cap</strong> ({@code kmosf.chairfill.prevention.max-per-contact-per-window} over
 * {@code …window-hours}) prevents a client with several upcoming bookings from being spammed.
 *
 * <h2>Idempotent + best-effort (plan HARD GATES 2)</h2>
 * A {@link ReminderLog} row is inserted <strong>FIRST</strong>, unique on (tenant, booking) — a
 * re-fired {@code BOOKING_RISK_SCORED} (nightly re-score, restart, concurrent emit) loses on a
 * {@code DuplicateKeyException} and does ZERO duplicate work. Every external call (Claude, Twilio,
 * the deposit mint) is wrapped {@code onErrorResume}: a Claude/budget failure degrades to a generic
 * template; an SMS failure (e.g. no Twilio connection) or a deposit-mint failure logs and degrades —
 * a booking is <strong>never</strong> dropped or corrupted.
 *
 * <h2>Blast radius zero (plan HARD GATES 3)</h2>
 * Wired as a {@code @Bean} in {@code ChairFillAutoConfiguration} ({@code @ConditionalOnProperty}
 * {@code kmosf.modules.chairfill.enabled} + {@code @ConditionalOnBean(SalonBookingService.class)}),
 * so the bean does not exist for non-chairfill servers. {@code BOOKING_RISK_SCORED} is only ever
 * emitted by the CF-1 scorer for chairfill tenants, and {@link #process} additionally re-checks
 * {@code Tenant.enabledModules} membership (defense-in-depth), so the subscriber is a hard no-op for
 * every non-chairfill tenant regardless of who emits.
 * Reused error codes only: AI {@code 1200-1203} (via {@link ReminderCopyService}), Twilio
 * {@code 2530-2532}, salon deposit/booking {@code 2900}. CF-2 mints none of its own (4225-4229
 * reserved).
 */
@Slf4j
public class RiskTieredPreventionService {

    /** A Contact carrying this tag has opted out of SMS (STOP). No reminder is ever sent to them. */
    public static final String SMS_OPT_OUT_TAG = "sms-opt-out";

    private final DomainEventPublisher events;
    private final TenantRepository tenantRepository;
    private final BookingRepository bookings;
    private final ContactRepository contacts;
    private final StaffMemberRepository staff;
    private final ServiceMenuRepository menus;
    private final SalonBookingService bookingService;
    private final ReminderCopyService reminderCopy;
    private final TwilioSmsService twilioSms;
    private final ReminderLogRepository reminderLogs;

    private final BigDecimal depositRate;
    private final BigDecimal depositMin;
    private final String brandTone;
    private final int maxPerContactPerWindow;
    private final long windowHours;

    public RiskTieredPreventionService(DomainEventPublisher events,
                                       TenantRepository tenantRepository,
                                       BookingRepository bookings,
                                       ContactRepository contacts,
                                       StaffMemberRepository staff,
                                       ServiceMenuRepository menus,
                                       SalonBookingService bookingService,
                                       ReminderCopyService reminderCopy,
                                       TwilioSmsService twilioSms,
                                       ReminderLogRepository reminderLogs,
                                       BigDecimal depositRate,
                                       BigDecimal depositMin,
                                       String brandTone,
                                       int maxPerContactPerWindow,
                                       long windowHours) {
        this.events = events;
        this.tenantRepository = tenantRepository;
        this.bookings = bookings;
        this.contacts = contacts;
        this.staff = staff;
        this.menus = menus;
        this.bookingService = bookingService;
        this.reminderCopy = reminderCopy;
        this.twilioSms = twilioSms;
        this.reminderLogs = reminderLogs;
        this.depositRate = depositRate;
        this.depositMin = depositMin;
        this.brandTone = brandTone == null ? "" : brandTone;
        this.maxPerContactPerWindow = maxPerContactPerWindow;
        this.windowHours = windowHours;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.BOOKING_RISK_SCORED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("RiskTieredPreventionService: error processing "
                                    + "BOOKING_RISK_SCORED for tenant {}", e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code BOOKING_RISK_SCORED} event end-to-end and
     * return when done (so an IT can drive it deterministically without the live event bus + a sleep).
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        UUID bookingId = asUuid(event.payload().get("bookingId"));
        String tier = asString(event.payload().get("riskTier"));
        if (bookingId == null || tier == null) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return process(tenantId, bookingId, tier)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Void> process(UUID tenantId, UUID bookingId, String tier) {
        // Defense-in-depth (HARD GATE 3): even if some other emitter ever fires BOOKING_RISK_SCORED
        // for a non-chairfill tenant, the prevention is a hard no-op. The only real emitter (the CF-1
        // scorer) already gates on this, so this is belt-and-braces — never a live action for a tenant
        // that hasn't enabled ChairFill.
        return tenantRepository.findById(tenantId)
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(ChairFillAutoConfiguration.MODULE_KEY))
                .flatMap(t -> actOnBooking(tenantId, bookingId, tier))
                .then();
    }

    private Mono<Void> actOnBooking(UUID tenantId, UUID bookingId, String tier) {
        return bookings.findById(bookingId)
                .flatMap(booking -> {
                    if (!isActionableUpcoming(booking)) {
                        return Mono.empty();
                    }
                    UUID contactId = booking.getContactId();
                    if (contactId == null) {
                        return Mono.empty(); // no contact to reach
                    }
                    return contacts.findByTenantIdAndId(tenantId, contactId)
                            .flatMap(contact -> gateThenAct(tenantId, booking, contact, tier))
                            // No contact record at all -> nothing to text; skip silently.
                            .switchIfEmpty(Mono.empty());
                });
    }

    /**
     * Consent + frequency-cap gates, then claim the booking (ledger-insert-FIRST) and act. Returns
     * empty (a clean skip) when a gate blocks or the booking was already actioned.
     */
    private Mono<Void> gateThenAct(UUID tenantId, Booking booking, Contact contact, String tier) {
        if (hasOptedOut(contact)) {
            log.debug("CF-2: contact {} opted out of SMS — skipping prevention for booking {}",
                    contact.getId(), booking.getId());
            return Mono.empty();
        }
        String phone = firstPhone(contact);
        if (phone == null) {
            log.debug("CF-2: contact {} has no phone — skipping prevention for booking {}",
                    contact.getId(), booking.getId());
            return Mono.empty();
        }
        Instant windowStart = Instant.now().minus(Duration.ofHours(windowHours));
        return reminderLogs.countByTenantIdAndContactIdAndSentAtAfter(tenantId, contact.getId(), windowStart)
                .defaultIfEmpty(0L)
                .flatMap(recent -> {
                    if (recent >= maxPerContactPerWindow) {
                        log.debug("CF-2: contact {} hit the frequency cap ({} in {}h) — skipping booking {}",
                                contact.getId(), recent, windowHours, booking.getId());
                        return Mono.empty();
                    }
                    return claimThenAct(tenantId, booking, contact, phone, tier);
                });
    }

    /**
     * Insert the {@link ReminderLog} FIRST (unique (tenant, booking)); a concurrent / re-fired event
     * loses on {@code DuplicateKeyException} = zero duplicate action. Only the winner proceeds to act.
     */
    private Mono<Void> claimThenAct(UUID tenantId, Booking booking, Contact contact,
                                    String phone, String tier) {
        boolean high = NoShowRisk.TIER_HIGH.equals(tier);
        ReminderLog ledger = ReminderLog.builder()
                .tenantId(tenantId)
                .bookingId(booking.getId())
                .contactId(contact.getId())
                .riskTier(tier)
                .depositRequired(high)
                .personalized(false)
                .sentAt(Instant.now())
                .build();
        return reminderLogs.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("CF-2: booking {} already actioned (ledger present) — zero duplicate",
                            booking.getId());
                    return Mono.empty();
                })
                .flatMap(saved -> act(booking, contact, phone, high, saved));
    }

    private Mono<Void> act(Booking booking, Contact contact, String phone, boolean high, ReminderLog ledger) {
        // Resolve the service name (for the copy) + price (for the HIGH deposit amount), best-effort.
        return resolveMenuItem(booking)
                .defaultIfEmpty(new ServiceMenuItem())
                .flatMap(item -> resolveStylistName(booking)
                        .defaultIfEmpty("")
                        .flatMap(stylistName -> {
                            Mono<Void> depositStep = high
                                    ? requireDeposit(booking, item)
                                    : Mono.empty();
                            return depositStep.then(
                                    buildAndSend(booking, contact, phone, item, stylistName, high, ledger));
                        }));
    }

    /** HIGH path — require a deposit on the (already-created) booking via the reused salon path. */
    private Mono<Void> requireDeposit(Booking booking, ServiceMenuItem item) {
        BigDecimal amount = depositAmount(booking, item);
        return bookingService.requireDepositNow(booking.getId(), amount)
                .doOnSuccess(b -> log.info("CF-2: required a {} deposit on HIGH-risk booking {}",
                        amount, booking.getId()))
                .onErrorResume(err -> {
                    // Best-effort: a deposit-mint failure must not drop the booking or block the SMS.
                    log.warn("CF-2: failed to require deposit on booking {} (continuing): {}",
                            booking.getId(), err.toString());
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Draft the Claude-personalized copy (best-effort → generic fallback), send the SMS (best-effort),
     * and stamp the ledger with the outcome. Never throws.
     */
    private Mono<Void> buildAndSend(Booking booking, Contact contact, String phone,
                                    ServiceMenuItem item, String stylistName, boolean high,
                                    ReminderLog ledger) {
        String firstName = contact.getFirstName();
        String serviceName = item.getName() != null ? item.getName() : booking.getServiceMenuItemName();
        String when = humanWhen(booking.getScheduledStart());

        ReminderCopyService.ReminderContext rc = new ReminderCopyService.ReminderContext(
                firstName, emptyToNull(stylistName), serviceName, when, brandTone, high);

        return reminderCopy.draftReminder(rc)
                .map(text -> new Drafted(text, true))
                .onErrorResume(err -> {
                    // Claude / budget / missing-key (1200/1202/1203) -> generic template, never block.
                    log.info("CF-2: reminder personalization unavailable for booking {} "
                            + "(falling back to generic): {}", booking.getId(), err.toString());
                    return Mono.just(new Drafted(
                            genericTemplate(firstName, emptyToNull(stylistName), serviceName, when, high),
                            false));
                })
                .flatMap(drafted -> sendSms(phone, drafted.text())
                        .then(reminderLogs.save(ledger.toBuilder()
                                        .personalized(drafted.personalized())
                                        .build())
                                .then()));
    }

    private Mono<Void> sendSms(String phone, String body) {
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(new PhoneContact(phone))
                .body(body)
                .build();
        return twilioSms.sendSms(req)
                .doOnSuccess(ok -> log.debug("CF-2: prevention SMS sent to {}", phone))
                .onErrorResume(err -> {
                    // Best-effort: no Twilio connection (2501/2531/2532) or a send failure must not
                    // corrupt the booking or abort the stream — log + degrade.
                    log.warn("CF-2: prevention SMS send failed for {} (continuing): {}",
                            phone, err.toString());
                    return Mono.just(false);
                })
                .then();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean isActionableUpcoming(Booking b) {
        BookingStatus s = b.getStatus();
        boolean upcomingStatus = s == BookingStatus.CONFIRMED || s == BookingStatus.PENDING_DEPOSIT;
        boolean future = b.getScheduledStart() != null && b.getScheduledStart().isAfter(Instant.now());
        return upcomingStatus && future;
    }

    private boolean hasOptedOut(Contact contact) {
        Set<String> tags = contact.getTags();
        return tags != null && tags.contains(SMS_OPT_OUT_TAG);
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

    /** Deposit amount for a HIGH-risk booking: an existing set amount wins; else price × rate, floored. */
    private BigDecimal depositAmount(Booking booking, ServiceMenuItem item) {
        if (booking.getDepositAmount() != null && booking.getDepositAmount().compareTo(BigDecimal.ZERO) > 0) {
            return booking.getDepositAmount();
        }
        BigDecimal price = item.getPrice();
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            // No price to base a percentage on — fall back to the configured minimum so the HIGH-risk
            // booking still gets a (modest) deposit ask rather than silently none.
            return depositMin;
        }
        BigDecimal pct = price.multiply(depositRate).setScale(2, RoundingMode.HALF_UP);
        return pct.compareTo(depositMin) < 0 ? depositMin : pct;
    }

    private Mono<ServiceMenuItem> resolveMenuItem(Booking booking) {
        String itemId = booking.getServiceMenuItemId();
        if (itemId == null) return Mono.empty();
        return menus.findAllByTenantId(booking.getTenantId())
                .flatMapIterable(m -> m.getServices() == null ? List.of() : m.getServices())
                .filter(i -> itemId.equals(i.getId()))
                .next();
    }

    private Mono<String> resolveStylistName(Booking booking) {
        if (booking.getStaffMemberId() == null) return Mono.empty();
        return staff.findById(booking.getStaffMemberId())
                .map(StaffMember::getDisplayName)
                .filter(n -> n != null && !n.isBlank());
    }

    private static final DateTimeFormatter WHEN_FMT =
            DateTimeFormatter.ofPattern("EEEE 'at' h:mm a", Locale.US);

    private String humanWhen(Instant when) {
        if (when == null) return null;
        // UTC is fine for the PoC copy; per-tenant timezone is a CF-5 nicety.
        return WHEN_FMT.format(when.atZone(ZoneId.of("UTC")));
    }

    /** The deterministic fallback used when Claude is unavailable — never blocks the booking. */
    private String genericTemplate(String firstName, String stylistName, String service,
                                   String when, boolean confirm) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi");
        if (firstName != null && !firstName.isBlank()) sb.append(' ').append(firstName.trim());
        sb.append("! This is a reminder about your upcoming appointment");
        if (stylistName != null && !stylistName.isBlank()) sb.append(" with ").append(stylistName.trim());
        if (service != null && !service.isBlank()) sb.append(" for ").append(service.trim());
        if (when != null && !when.isBlank()) sb.append(' ').append(when);
        sb.append('.');
        if (confirm) {
            sb.append(" Please reply to confirm. Reply STOP to opt out.");
        } else {
            sb.append(" See you soon! Reply STOP to opt out.");
        }
        return sb.toString();
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

    /** Internal carrier for a drafted reminder + whether it was Claude-personalized. */
    private record Drafted(String text, boolean personalized) {
    }
}
