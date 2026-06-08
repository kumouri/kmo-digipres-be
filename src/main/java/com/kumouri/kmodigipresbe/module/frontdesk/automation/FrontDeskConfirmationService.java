package com.kumouri.kmodigipresbe.module.frontdesk.automation;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.ai.ConfirmationCopyService;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * FrontDesk IQ (FD-2) — risk-tiered no-show <strong>confirmation</strong>. A dedicated
 * {@code @PostConstruct} subscriber on {@link DomainEventType#APPOINTMENT_RISK_SCORED} (the FD-1 stamp),
 * mirroring the chairfill CF-2
 * {@link com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService}'s
 * {@code events.stream().filter(type).flatMap(handle)} pattern + synthetic {@link TenantContext}.
 *
 * <h2>Branch (plan FD-2) — like CF-2 <em>minus the deposit</em></h2>
 * Health practices do not take deposits, so unlike CF-2 there is <strong>no deposit / invoice path</strong>:
 * <ul>
 *   <li><strong>HIGH</strong> → an <em>extra confirmation</em> SMS (ask them to reply to confirm).</li>
 *   <li><strong>LOW / MEDIUM</strong> → a single light <em>reminder</em> SMS.</li>
 * </ul>
 * The copy is optionally Claude-personalized via {@link ConfirmationCopyService} but is
 * <strong>provably PHI-free (fence F3)</strong>: it is GENERIC ("time for your visit"), and NEVER names a
 * procedure, provider, department, or visit-type. The {@link ConfirmationCopyService.ConfirmationContext}
 * physically carries no clinical field, and the generic fallback template carries none either — a
 * release-blocking IT asserts a forbidden-token set is absent from every outbound body.
 *
 * <h2>TCPA / consent (plan §5 risk)</h2>
 * Before any send: the contact must have a phone and must NOT carry the {@value #SMS_OPT_OUT_TAG} tag
 * (honors STOP); and a <strong>per-contact rolling frequency cap</strong>
 * ({@code kmosf.frontdesk.confirmation.max-per-contact-per-window} over {@code …window-hours}) prevents a
 * patient with several upcoming appointments from being spammed.
 *
 * <h2>Idempotent + best-effort (plan HARD GATES 3 + 4)</h2>
 * A {@link ConfirmationLog} row is inserted <strong>FIRST</strong>, unique on (tenant, appointment) — a
 * re-fired {@code APPOINTMENT_RISK_SCORED} (nightly re-score, restart, concurrent emit) loses on a
 * {@code DuplicateKeyException} and does ZERO duplicate work. Every external call (Claude, Twilio) is
 * wrapped {@code onErrorResume}: a Claude/budget failure degrades to the generic template; an SMS failure
 * logs and degrades — an appointment is <strong>never</strong> dropped or corrupted.
 *
 * <h2>Blast radius zero (plan HARD GATES 2)</h2>
 * Wired as a {@code @Bean} in {@link FrontDeskAutoConfiguration}
 * ({@code @ConditionalOnProperty} {@code kmosf.modules.frontdesk.enabled}), so the bean does not exist for
 * non-frontdesk servers. {@code APPOINTMENT_RISK_SCORED} is only ever emitted by the FD-1 scorer for
 * frontdesk tenants, and {@link #process} additionally re-checks {@code Tenant.enabledModules} membership
 * (defense-in-depth), so the subscriber is a hard no-op for every non-frontdesk tenant regardless of who
 * emits. Reused error codes only: AI {@code 1200-1203} (via {@link ConfirmationCopyService}), Twilio
 * {@code 2530-2532}. FD-2 mints none of its own (4280-4284 reserved).
 */
@Slf4j
public class FrontDeskConfirmationService {

    /** A Contact carrying this tag has opted out of SMS (STOP). No confirmation is ever sent to them. */
    public static final String SMS_OPT_OUT_TAG = "sms-opt-out";

    private final DomainEventPublisher events;
    private final TenantRepository tenantRepository;
    private final AppointmentRepository appointments;
    private final ContactRepository contacts;
    private final ConfirmationCopyService confirmationCopy;
    private final TwilioSmsService twilioSms;
    private final ConfirmationLogRepository confirmationLogs;

    private final String brandTone;
    private final int maxPerContactPerWindow;
    private final long windowHours;

    public FrontDeskConfirmationService(DomainEventPublisher events,
                                        TenantRepository tenantRepository,
                                        AppointmentRepository appointments,
                                        ContactRepository contacts,
                                        ConfirmationCopyService confirmationCopy,
                                        TwilioSmsService twilioSms,
                                        ConfirmationLogRepository confirmationLogs,
                                        String brandTone,
                                        int maxPerContactPerWindow,
                                        long windowHours) {
        this.events = events;
        this.tenantRepository = tenantRepository;
        this.appointments = appointments;
        this.contacts = contacts;
        this.confirmationCopy = confirmationCopy;
        this.twilioSms = twilioSms;
        this.confirmationLogs = confirmationLogs;
        this.brandTone = brandTone == null ? "" : brandTone;
        this.maxPerContactPerWindow = maxPerContactPerWindow;
        this.windowHours = windowHours;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.APPOINTMENT_RISK_SCORED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("FrontDeskConfirmationService: error processing "
                                    + "APPOINTMENT_RISK_SCORED for tenant {}", e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code APPOINTMENT_RISK_SCORED} event end-to-end and
     * return when done (so an IT can drive it deterministically without the live event bus + a sleep).
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        UUID appointmentId = asUuid(event.payload().get("appointmentId"));
        String tier = asString(event.payload().get("riskTier"));
        if (appointmentId == null || tier == null) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return process(tenantId, appointmentId, tier)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Void> process(UUID tenantId, UUID appointmentId, String tier) {
        // Defense-in-depth (HARD GATE 2): even if some other emitter ever fires APPOINTMENT_RISK_SCORED
        // for a non-frontdesk tenant, the confirmation is a hard no-op. The only real emitter (the FD-1
        // scorer) already gates on this, so this is belt-and-braces.
        return tenantRepository.findById(tenantId)
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(FrontDeskAutoConfiguration.MODULE_KEY))
                .flatMap(t -> actOnAppointment(tenantId, appointmentId, tier))
                .then();
    }

    private Mono<Void> actOnAppointment(UUID tenantId, UUID appointmentId, String tier) {
        return appointments.findByIdAndTenantId(appointmentId, tenantId)
                .flatMap(appointment -> {
                    if (!isActionableUpcoming(appointment)) {
                        return Mono.empty();
                    }
                    UUID contactId = appointment.getContactId();
                    if (contactId == null) {
                        return Mono.empty(); // no contact to reach
                    }
                    return contacts.findByTenantIdAndId(tenantId, contactId)
                            .flatMap(contact -> gateThenAct(tenantId, appointment, contact, tier))
                            .switchIfEmpty(Mono.empty());
                });
    }

    /**
     * Consent + frequency-cap gates, then claim the appointment (ledger-insert-FIRST) and act. Returns
     * empty (a clean skip) when a gate blocks or the appointment was already actioned.
     */
    private Mono<Void> gateThenAct(UUID tenantId, Appointment appointment, Contact contact, String tier) {
        if (hasOptedOut(contact)) {
            log.debug("FD-2: contact {} opted out of SMS — skipping confirmation for appointment {}",
                    contact.getId(), appointment.getId());
            return Mono.empty();
        }
        String phone = firstPhone(contact);
        if (phone == null) {
            log.debug("FD-2: contact {} has no phone — skipping confirmation for appointment {}",
                    contact.getId(), appointment.getId());
            return Mono.empty();
        }
        Instant windowStart = Instant.now().minus(Duration.ofHours(windowHours));
        return confirmationLogs.countByTenantIdAndContactIdAndSentAtAfter(tenantId, contact.getId(), windowStart)
                .defaultIfEmpty(0L)
                .flatMap(recent -> {
                    if (recent >= maxPerContactPerWindow) {
                        log.debug("FD-2: contact {} hit the frequency cap ({} in {}h) — skipping appointment {}",
                                contact.getId(), recent, windowHours, appointment.getId());
                        return Mono.empty();
                    }
                    return claimThenAct(tenantId, appointment, contact, phone, tier);
                });
    }

    /**
     * Insert the {@link ConfirmationLog} FIRST (unique (tenant, appointment)); a concurrent / re-fired
     * event loses on {@code DuplicateKeyException} = zero duplicate action. Only the winner proceeds.
     */
    private Mono<Void> claimThenAct(UUID tenantId, Appointment appointment, Contact contact,
                                    String phone, String tier) {
        boolean high = NoShowRisk.TIER_HIGH.equals(tier);
        ConfirmationLog ledger = ConfirmationLog.builder()
                .tenantId(tenantId)
                .appointmentId(appointment.getId())
                .contactId(contact.getId())
                .riskTier(tier)
                .confirmation(high)
                .personalized(false)
                .sentAt(Instant.now())
                .build();
        return confirmationLogs.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("FD-2: appointment {} already actioned (ledger present) — zero duplicate",
                            appointment.getId());
                    return Mono.empty();
                })
                .flatMap(saved -> buildAndSend(appointment, contact, phone, high, saved));
    }

    /**
     * Draft the Claude-personalized copy (best-effort → generic fallback), send the SMS (best-effort), and
     * stamp the ledger with the outcome. Never throws. <strong>The copy is GENERIC by construction (fence
     * F3):</strong> no procedure / provider / visit-type is ever passed to the drafter or built into the
     * fallback.
     */
    private Mono<Void> buildAndSend(Appointment appointment, Contact contact, String phone, boolean high,
                                    ConfirmationLog ledger) {
        String firstName = contact.getFirstName();
        String when = humanWhen(appointment.getScheduledStart());

        ConfirmationCopyService.ConfirmationContext cc =
                new ConfirmationCopyService.ConfirmationContext(firstName, when, brandTone, high);

        return confirmationCopy.draftConfirmation(cc)
                .map(text -> new Drafted(text, true))
                .onErrorResume(err -> {
                    // Claude / budget / missing-key (1200/1202/1203) -> generic template, never block.
                    log.info("FD-2: confirmation personalization unavailable for appointment {} "
                            + "(falling back to generic): {}", appointment.getId(), err.toString());
                    return Mono.just(new Drafted(genericTemplate(firstName, when, high), false));
                })
                .flatMap(drafted -> sendSms(phone, drafted.text())
                        .then(confirmationLogs.save(ledger.toBuilder()
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
                .doOnSuccess(ok -> log.debug("FD-2: confirmation SMS sent to {}", phone))
                .onErrorResume(err -> {
                    // Best-effort: no Twilio connection (2501/2531/2532) or a send failure must not
                    // corrupt the appointment or abort the stream — log + degrade.
                    log.warn("FD-2: confirmation SMS send failed for {} (continuing): {}",
                            phone, err.toString());
                    return Mono.just(false);
                })
                .then();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private boolean isActionableUpcoming(Appointment a) {
        AppointmentStatus s = a.getStatus();
        boolean upcomingStatus = s == AppointmentStatus.SCHEDULED || s == AppointmentStatus.CONFIRMED;
        boolean future = a.getScheduledStart() != null && a.getScheduledStart().isAfter(Instant.now());
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

    private static final DateTimeFormatter WHEN_FMT =
            DateTimeFormatter.ofPattern("EEEE 'at' h:mm a", Locale.US);

    private String humanWhen(Instant when) {
        if (when == null) return null;
        // UTC is fine for the PoC copy; per-tenant timezone is an FD-5 nicety.
        return WHEN_FMT.format(when.atZone(ZoneId.of("UTC")));
    }

    /**
     * The deterministic fallback used when Claude is unavailable — never blocks the appointment.
     * <strong>GENERIC by construction (fence F3):</strong> it references only the first name and the time —
     * never a procedure, provider, department, or visit-type. There is no parameter through which a
     * clinical token could enter.
     */
    private String genericTemplate(String firstName, String when, boolean confirm) {
        StringBuilder sb = new StringBuilder();
        sb.append("Hi");
        if (firstName != null && !firstName.isBlank()) sb.append(' ').append(firstName.trim());
        sb.append("! This is a reminder about your upcoming visit");
        if (when != null && !when.isBlank()) sb.append(' ').append(when);
        sb.append('.');
        if (confirm) {
            sb.append(" Please reply to confirm. Reply STOP to opt out.");
        } else {
            sb.append(" See you soon! Reply STOP to opt out.");
        }
        return sb.toString();
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

    /** Internal carrier for a drafted confirmation + whether it was Claude-personalized. */
    private record Drafted(String text, boolean personalized) {
    }
}
