package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.module.waitlist.WaitlistAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineEntryRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T7 (Health "RescheduleFlow") — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the {@code SwitchboardDemoSeeder} / {@code FrontDeskNurtureDemoSeeder}
 * precedent) that stands up a fictional clinic <strong>"Lakeside Family Dental (RescheduleFlow)"</strong>
 * wired for the full gap-fill loop: frontdesk + waitlist + responder enabled, a booked
 * {@link Appointment}, a ranked waitlist, and a {@link ResponderConfig vertical="health-reschedule"} so an
 * inbound YES claims the freed slot.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-health-reschedule")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code lakeside-reschedule} — re-running does nothing. All data is
 * <strong>fictional</strong>; a live RescheduleFlow on real patient appointment data needs the
 * separately-priced, BAA-gated compliance tier (go-live notes).
 *
 * <h2>What it seeds</h2>
 * <ul>
 *   <li>Tenant "Lakeside Family Dental (RescheduleFlow)" + an ADMIN user; modules {@code frontdesk} +
 *       {@code waitlist} + {@code responder} enabled; a non-zero {@code aiBudgetUsd} (so the YES classifier
 *       can run).</li>
 *   <li>An {@code IntegrationConnection(twilio)} (sandbox {@code authToken} + a tracked {@code fromNumber};
 *       {@code smsMode} UNSET so an inbound YES falls through to the E2 responder router) and an
 *       {@code IntegrationConnection(anthropic)} (sandbox {@code apiKey} so the classifier resolves a key).</li>
 *   <li>A {@code ResponderConfig(vertical="health-reschedule")} naming an affirmative intent + a STOP-aware
 *       cap, so the {@link RescheduleWaitlistIntentHandler} activates on a "yes".</li>
 *   <li>A booked {@link Appointment} (SCHEDULED, logistics only) to cancel, plus 2 waitlisted patients (a
 *       reliable regular ranks above a flaky one).</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-health-reschedule,dev}) "A patient cancels a 2pm cleaning.
 *       Normally that chair sits empty — lost revenue."</li>
 *   <li>Cancel the booked appointment ({@code PUT /api/v1/frontdesk/appointments/{id}} with
 *       {@code status=CANCELLED}) → the top waitlisted patient instantly gets a generic
 *       "a spot just opened — reply YES to take it" SMS (PHI-free; no procedure/provider/condition).</li>
 *   <li>The patient texts "YES" → it claims the slot (the atomic first-YES guard) and a brand-new
 *       <strong>PHI-free</strong> {@code Appointment} is created for them — the front desk never touched the
 *       chart.</li>
 *   <li>{@code GET /api/v1/frontdesk/reschedule/fill-stats} → "Look: 1 cancellation, offers sent, 1 claim,
 *       1 slot filled — the empty chair recovered automatically, PHI-free."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-health-reschedule")
public class RescheduleFlowDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "lakeside-reschedule";
    private static final String ADMIN_EMAIL = "frontdesk@lakeside-reschedule.example";
    private static final String TRACKED_PHONE = "+12145550400";
    private static final String RELIABLE_PHONE = "+12145550401";
    private static final String FLAKY_PHONE = "+12145550402";

    /** The affirmative intent the classifier emits for a patient's "yes" (the handler claims it). */
    private static final String INTENT_ACCEPT_SLOT = "ACCEPT_SLOT";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ContactRepository contacts;
    private final AppointmentRepository appointments;
    private final WaitlistEngineEntryRepository waitlistEntries;
    private final ResponderConfigRepository responderConfigs;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public RescheduleFlowDemoSeeder(TenantRepository tenants,
                                    UserRepository users,
                                    ContactRepository contacts,
                                    AppointmentRepository appointments,
                                    WaitlistEngineEntryRepository waitlistEntries,
                                    ResponderConfigRepository responderConfigs,
                                    IntegrationConnectionRepository connections,
                                    PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.contacts = contacts;
        this.appointments = appointments;
        this.waitlistEntries = waitlistEntries;
        this.responderConfigs = responderConfigs;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("RescheduleFlowDemoSeeder failed", err));
    }

    private Mono<Void> seed() {
        return tenants.findBySlug(TENANT_SLUG)
                .doOnNext(t -> log.info("Demo tenant {} already seeded — skipping", TENANT_SLUG))
                .switchIfEmpty(Mono.defer(this::seedFresh))
                .then();
    }

    private Mono<Tenant> seedFresh() {
        UUID tenantId = UUID.randomUUID();
        Tenant tenant = Tenant.builder()
                .id(tenantId)
                .slug(TENANT_SLUG)
                .displayName("Lakeside Family Dental (RescheduleFlow)")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        FrontDeskAutoConfiguration.MODULE_KEY,
                        WaitlistAutoConfiguration.MODULE_KEY,
                        ResponderAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Lakeside RescheduleFlow) + booked appointment + ranked waitlist "
                + "+ health-reschedule responder config", TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));

        UUID reliableId = UUID.randomUUID();
        UUID flakyId = UUID.randomUUID();
        UUID bookedContactId = UUID.randomUUID();
        Instant bookedStart = Instant.now().plus(Duration.ofHours(3));

        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(connections.save(anthropicConnection(tenantId)))
                        .then(responderConfigs.save(responderConfig(tenantId)))
                        // The patient whose appointment will be cancelled (the freed slot).
                        .then(contacts.save(person(tenantId, bookedContactId, "Pat", "Booked", null)))
                        .then(appointments.save(bookedAppointment(tenantId, bookedContactId, bookedStart)))
                        // Two waitlisted patients: a reliable regular ranks above a flaky one.
                        .then(contacts.save(person(tenantId, reliableId, "Rita", "Reliable", RELIABLE_PHONE)))
                        .then(waitlistEntries.save(entry(tenantId, reliableId, 0, 6,
                                Instant.now().minus(Duration.ofDays(20)))))
                        .then(contacts.save(person(tenantId, flakyId, "Finn", "Flaky", FLAKY_PHONE)))
                        .then(waitlistEntries.save(entry(tenantId, flakyId, 2, 1,
                                Instant.now().minus(Duration.ofDays(40)))))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — cancel the booked appointment to watch "
                        + "the top waitlisted patient ({}) get a PHI-free 'a spot opened, reply YES' offer",
                        TENANT_SLUG, RELIABLE_PHONE));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("reschedule-demo-password"))
                .displayName("Lakeside Front Desk")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: smsMode UNSET (inbound YES → E2 responder) + a sandbox token + tracked number. */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "demo-sandbox-authtoken",
                        "fromNumber", TRACKED_PHONE)))
                .config(new HashMap<>())
                .build();
    }

    /** Anthropic connection: a sandbox apiKey so the inbound-YES classifier resolves a key (no live call). */
    private IntegrationConnection anthropicConnection(UUID tenantId) {
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", "sk-ant-demo-sandbox-key")))
                .config(new HashMap<>())
                .build();
    }

    /**
     * The health-reschedule responder config: vertical=health-reschedule + a single affirmative intent so
     * the classifier maps a patient's "yes" to {@code ACCEPT_SLOT} and the
     * {@link RescheduleWaitlistIntentHandler} claims the freed slot.
     */
    private ResponderConfig responderConfig(UUID tenantId) {
        return ResponderConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .enabled(true)
                .vertical(RescheduleWaitlistIntentHandler.VERTICAL)
                .intents(List.of(new IntentDefinition(INTENT_ACCEPT_SLOT,
                        "The patient is accepting/confirming an offered open appointment slot "
                                + "(e.g. \"yes\", \"sure\", \"I'll take it\").")))
                .replyCapPerContactPerDay(8)
                .build();
    }

    /** A PHI-free contact (logistics only). */
    private Contact person(UUID tenantId, UUID id, String first, String last, String phone) {
        Contact.ContactBuilder b = Contact.builder()
                .id(id)
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName(first)
                .displayName(first + " " + last);
        if (phone != null) {
            b.phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()));
        }
        return b.build();
    }

    /** The booked PHI-free appointment to cancel (logistics only — no clinical field, fence F1). */
    private Appointment bookedAppointment(UUID tenantId, UUID contactId, Instant start) {
        return Appointment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .scheduledStart(start)
                .scheduledEnd(start.plus(30, ChronoUnit.MINUTES))
                .status(AppointmentStatus.SCHEDULED)
                .visitTypeBucket(VisitTypeBucket.OTHER)
                .build();
    }

    /** A PHI-free waitlist entry (logistics-only show stats) for the health-appt slot type. */
    private WaitlistEntry entry(UUID tenantId, UUID contactId, int noShows, int visits, Instant lastVisit) {
        return WaitlistEntry.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .slotType(FrontDeskSlotMaterializer.SLOT_TYPE)
                .smsOptIn(true)
                .status(WaitlistEntry.Status.OPEN)
                .priorNoShowCount(noShows)
                .priorVisitCount(visits)
                .lastVisitAt(lastVisit)
                .build();
    }
}
