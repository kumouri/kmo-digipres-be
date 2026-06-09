package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * T2 (Health "RevenueRevive") — the demo seed for the 60-second "watch this" (the health twin of T1's
 * {@code RealEstateNurtureDemoSeeder}). A {@code @Profile}-gated, idempotent {@code CommandLineRunner} (the
 * {@code DataSeeder}/T1 precedent) that stands up a fictional dental practice <strong>"Bright Smiles
 * Dental"</strong> with a <strong>dormant-patient database</strong> + the health nurture campaign, so the
 * demo moment runs end-to-end on fictional data.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-frontdesk")}, so it is absent from dev / CI / prod default runs (it only
 * activates when {@code SPRING_PROFILES_ACTIVE} includes {@code demo-frontdesk}). Idempotent on the tenant
 * slug {@code bright-smiles-dental} — re-running does nothing.
 *
 * <h2>PHI-free demo data (the headline)</h2>
 * The dormant cohort is described in <strong>logistics terms only</strong>: each patient gets a backdated
 * {@code Activity(CONTACT)} (the "last touch" recency the segmentation engine reads) AND a PHI-free
 * {@link Appointment} ({@code lastVisitAt} + a {@link VisitTypeBucket} scheduling category — never a
 * diagnosis/procedure). No clinical field is seeded anywhere, because none exists. The A-tier "high value"
 * cohort is a prior WON {@code Deal} (lifetime spend) — a logistics/value signal, not a clinical one.
 *
 * <h2>What it seeds</h2>
 * <ul>
 *   <li>Tenant "Bright Smiles Dental" + an ADMIN user, modules {@code frontdesk} + {@code nurture} +
 *       {@code responder} enabled, a non-zero {@code aiBudgetUsd} (so AI-personalized cadence copy can run —
 *       and be screened by the HIPAA filter).</li>
 *   <li>An {@code IntegrationConnection(twilio)} carrying {@code config.bookingLink} (the reply→rebook SMS
 *       target), {@code config.notifyPhone}/{@code notifyEmail} (the front desk) and a sandbox
 *       {@code authToken}. {@code smsMode} is <strong>unset</strong> — so an inbound positive reply falls
 *       through to the E2 responder seam + {@code FrontDeskNurtureReplyHandler}.</li>
 *   <li>A {@code ResponderConfig(vertical="frontdesk", enabled, intents=[POSITIVE_REPLY, ...])} so the
 *       reply→rebook handler activates.</li>
 *   <li>A <strong>dormant-patient DB</strong>: ~12 contacts spread across the A/B/C/D recency bands (some
 *       with WON deals to land in the high-value A tier; two opted-out to show the TCPA skip), each with a
 *       backdated {@code Activity} + a PHI-free {@code Appointment} so segmentation buckets them
 *       deterministically.</li>
 *   <li>The seeded health nurture campaign (the A/B/C/D segment thresholds + the PHI-free SMS/email cadence).</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-frontdesk,dev}) "Bright Smiles has 12 patients who lapsed
 *       months ago. Watch the front desk win them back — without ever touching the chart."</li>
 *   <li>{@code POST /api/v1/frontdesk/nurture/campaigns/{id}/segment-and-enroll} → "One click segments them
 *       by logistics only: recently-lapsed high-value (A), lapsed (B), long-lapsed (C), deep (D). No
 *       diagnosis, no procedure — the two opted-out patients were skipped automatically — TCPA-safe."</li>
 *   <li>Flip the runner on ({@code kmosf.modules.nurture-runner.enabled=true}) / trigger a sweep → "It texts
 *       each tier on its own cadence. Every message — even AI-personalized — passes a HIPAA lint first.
 *       Here's one the AI tried to write with 'thanks for being our patient, your crown looks great' —
 *       caught, a safe generic version sent instead."</li>
 *   <li>Reply "YES" as a patient (inbound SMS) → "A positive reply instantly exits the cadence and texts a
 *       rebook link — the patient is back on the schedule, hands-free."</li>
 *   <li>{@code GET /api/v1/frontdesk/nurture/campaigns/{id}/analytics} → "The practice sees which lapsed tier
 *       is reactivating — enrolled / replied / rebooked per bucket. The reactivation funnel, mined."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-frontdesk")
public class FrontDeskNurtureDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "bright-smiles-dental";
    public static final String CAMPAIGN_NAME = "Lapsed Patient Reactivation";
    private static final String ADMIN_EMAIL = "frontdesk@bright-smiles-dental.example";
    private static final String NOTIFY_PHONE = "+13145550100";
    private static final String NOTIFY_EMAIL = "frontdesk@bright-smiles-dental.example";
    private static final String BOOKING_LINK = "https://cal.example/bright-smiles/rebook";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ContactRepository contacts;
    private final ActivityRepository activities;
    private final DealRepository deals;
    private final AppointmentRepository appointments;
    private final NurtureCampaignRepository campaigns;
    private final ResponderConfigRepository responderConfigs;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public FrontDeskNurtureDemoSeeder(TenantRepository tenants,
                                      UserRepository users,
                                      ContactRepository contacts,
                                      ActivityRepository activities,
                                      DealRepository deals,
                                      AppointmentRepository appointments,
                                      NurtureCampaignRepository campaigns,
                                      ResponderConfigRepository responderConfigs,
                                      IntegrationConnectionRepository connections,
                                      PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.contacts = contacts;
        this.activities = activities;
        this.deals = deals;
        this.appointments = appointments;
        this.campaigns = campaigns;
        this.responderConfigs = responderConfigs;
        this.connections = connections;
        this.encoder = encoder;
        this.clock = Clock.systemUTC();
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("FrontDeskNurtureDemoSeeder failed", err));
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
                .displayName("Bright Smiles Dental")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        FrontDeskNurtureModuleKeys.FRONTDESK,
                        FrontDeskNurtureModuleKeys.NURTURE,
                        FrontDeskNurtureModuleKeys.RESPONDER))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Bright Smiles Dental) + dormant-patient DB + health nurture campaign",
                TENANT_SLUG);

        // All writes run under the tenant context (tenant-scoped repos auto-stamp/filter).
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(responderConfigs.save(responderConfig(tenantId)))
                        .then(campaigns.save(campaign(tenantId)))
                        .then(seedDormantPatients(tenantId))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — run segment-and-enroll, then the "
                        + "default-OFF nurture runner, to see T2 end-to-end", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("bright-smiles-demo-password"))
                .displayName("Bright Smiles Front Desk")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: bookingLink (reply→rebook target) + notify targets + a sandbox authToken; no smsMode. */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider(TwilioSmsService.PROVIDER)
                .secrets(new java.util.HashMap<>(java.util.Map.of("authToken", "demo-sandbox-authtoken")))
                .config(new java.util.HashMap<>(java.util.Map.of(
                        "bookingLink", BOOKING_LINK,
                        "notifyPhone", NOTIFY_PHONE,
                        "notifyEmail", NOTIFY_EMAIL)))
                .build();
    }

    /** Responder config (vertical=frontdesk) so the reply→rebook handler activates. */
    private ResponderConfig responderConfig(UUID tenantId) {
        return ResponderConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .enabled(true)
                .vertical(FrontDeskNurtureReplyHandler.VERTICAL)
                .intents(List.of(
                        new IntentDefinition("POSITIVE_REPLY",
                                "The patient is interested / wants to re-engage / says yes to getting back "
                                        + "on the schedule."),
                        new IntentDefinition("NOT_INTERESTED",
                                "The patient declines or is not interested right now.")))
                .replyCapPerContactPerDay(5)
                .build();
    }

    /** The health nurture campaign — A/B/C/D lapse segments + the PHI-free SMS/email cadence. */
    private NurtureCampaign campaign(UUID tenantId) {
        return NurtureCampaign.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .name(CAMPAIGN_NAME)
                .description("Reactivate lapsed patients with tiered, PHI-free cadences (logistics-only "
                        + "segmentation).")
                .active(true)
                .segments(List.of(
                        // A — recently-lapsed + high lifetime value (90-180d lapsed, >= $1,500 prior WON spend).
                        new NurtureSegmentDefinition(DormancyBucket.A, 90, 180,
                                new BigDecimal("1500"), null),
                        // B — lapsed (180-365d).
                        new NurtureSegmentDefinition(DormancyBucket.B, 180, 365, null, null),
                        // C — long-lapsed (365-540d).
                        new NurtureSegmentDefinition(DormancyBucket.C, 365, 540, null, null),
                        // D — deep-lapsed (540d+, open-ended).
                        new NurtureSegmentDefinition(DormancyBucket.D, 540, null, null, null)))
                .steps(List.of(
                        new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                                "Hi {firstName}, it's Bright Smiles Dental — it's been a while since we saw "
                                        + "you. Want to get back on the schedule? Reply YES and we'll find a "
                                        + "time.",
                                null, null, true, 0),
                        new NurtureCadenceStep(1, NurtureChannel.EMAIL, 3,
                                "We'd love to see you again, {firstName}",
                                "We'd love to see you again, {firstName}",
                                "Hi {firstName}, it's been a while since your last visit to Bright Smiles. "
                                        + "Reply and we'll get you back on the schedule at a time that works "
                                        + "for you.",
                                false, 2),
                        new NurtureCadenceStep(2, NurtureChannel.SMS, 7,
                                "Still here whenever you're ready, {firstName} — reply YES and we'll send a "
                                        + "time. Reply STOP to opt out.",
                                null, null, false, 4)))
                .maxTouchesPerContactPerWindow(3)
                .build();
    }

    /** ~12 lapsed patients across the A/B/C/D bands; 2 opted-out; A-tier patients get a WON deal for value. */
    private Mono<Void> seedDormantPatients(UUID tenantId) {
        Instant now = clock.instant();
        List<Mono<?>> writes = new ArrayList<>();

        // Bucket A (90-180d lapsed + high value): 3 patients, each with a prior WON deal >= $1,500.
        writes.add(lapsedPatient(tenantId, "Ava", "Nguyen", "+13145551001", 100, now, false,
                new BigDecimal("2200"), VisitTypeBucket.RECALL));
        writes.add(lapsedPatient(tenantId, "Marcus", "Bell", "+13145551002", 130, now, false,
                new BigDecimal("1800"), VisitTypeBucket.HYGIENE));
        writes.add(lapsedPatient(tenantId, "Priya", "Shah", "+13145551003", 170, now, false,
                new BigDecimal("3100"), VisitTypeBucket.FOLLOW_UP));

        // Bucket B (180-365d): 4 patients (one opted-out to show the TCPA skip), no value band.
        writes.add(lapsedPatient(tenantId, "Daniel", "Cruz", "+13145551004", 200, now, false, null,
                VisitTypeBucket.RECALL));
        writes.add(lapsedPatient(tenantId, "Sofia", "Reyes", "+13145551005", 260, now, false, null,
                VisitTypeBucket.HYGIENE));
        writes.add(lapsedPatient(tenantId, "Liam", "Walsh", "+13145551006", 300, now, true, null,
                VisitTypeBucket.ANNUAL_WELLNESS));
        writes.add(lapsedPatient(tenantId, "Grace", "Kim", "+13145551007", 350, now, false, null,
                VisitTypeBucket.RECALL));

        // Bucket C (365-540d): 3 patients (one opted-out).
        writes.add(lapsedPatient(tenantId, "Noah", "Patel", "+13145551008", 400, now, false, null,
                VisitTypeBucket.HYGIENE));
        writes.add(lapsedPatient(tenantId, "Emma", "Diaz", "+13145551009", 460, now, true, null,
                VisitTypeBucket.RECALL));
        writes.add(lapsedPatient(tenantId, "Owen", "Brooks", "+13145551010", 520, now, false, null,
                VisitTypeBucket.FOLLOW_UP));

        // Bucket D (540d+): 2 patients.
        writes.add(lapsedPatient(tenantId, "Maya", "Flores", "+13145551011", 600, now, false, null,
                VisitTypeBucket.RECALL));
        writes.add(lapsedPatient(tenantId, "Ethan", "Ward", "+13145551012", 800, now, false, null,
                VisitTypeBucket.HYGIENE));

        return Mono.when(writes);
    }

    /**
     * Seed one lapsed patient: a Contact (optionally opted-out via the {@code sms-opt-out} tag), a backdated
     * {@code Activity(CONTACT)} {@code daysLapsed} days ago (the "last touch" recency segmentation reads), a
     * PHI-free {@link Appointment} ({@code COMPLETED}, {@code lastVisitAt} = the same date, a logistics
     * {@link VisitTypeBucket} — never a clinical field), and — when {@code wonValue != null} — a WON
     * {@code Deal} so the value-band A tier can match. <strong>No clinical content is ever written.</strong>
     */
    private Mono<?> lapsedPatient(UUID tenantId, String first, String last, String phone, int daysLapsed,
                                  Instant now, boolean optedOut, BigDecimal wonValue,
                                  VisitTypeBucket visitTypeBucket) {
        UUID contactId = UUID.randomUUID();
        Instant lastSeen = now.minus(daysLapsed, ChronoUnit.DAYS);
        Set<String> tags = optedOut
                ? Set.of("frontdesk-patient", RiskTieredPreventionService.SMS_OPT_OUT_TAG)
                : Set.of("frontdesk-patient");
        Contact contact = Contact.builder()
                .id(contactId)
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName(first)
                .displayName(first + " " + last)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .emails(List.of(new EmailContact((first + "." + last + "@example.com").toLowerCase())))
                .tags(tags)
                .build();

        Activity activity = Activity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .type(ActivityType.NOTE)
                .direction(ActivityDirection.OUTBOUND)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contactId)
                .summary("Last visit — front-desk follow-up")
                .occurredAt(lastSeen)
                .build();

        // A PHI-free past appointment: logistics only (status/lastVisitAt/visitTypeBucket), no clinical field.
        Appointment appointment = Appointment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .status(AppointmentStatus.COMPLETED)
                .visitTypeBucket(visitTypeBucket)
                .scheduledStart(lastSeen)
                .scheduledEnd(lastSeen.plus(30, ChronoUnit.MINUTES))
                .lastVisitAt(lastSeen)
                .reminderCount(0)
                .build();

        Mono<?> base = contacts.save(contact)
                .then(activities.save(activity))
                .then(appointments.save(appointment));
        if (wonValue == null) {
            return base;
        }
        Deal won = Deal.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .title("Prior treatment plan — " + first + " " + last)
                .stage(PipelineStage.WON)
                .value(wonValue)
                .currency("USD")
                .primaryContactId(contactId)
                .stageChangedAt(lastSeen)
                .build();
        return base.then(deals.save(won));
    }

    /** Module keys used to seed {@code Tenant.enabledModules} (kept local to avoid a cross-module import knot). */
    private static final class FrontDeskNurtureModuleKeys {
        static final String FRONTDESK = "frontdesk";
        static final String NURTURE = "nurture";
        static final String RESPONDER = "responder";
    }
}
