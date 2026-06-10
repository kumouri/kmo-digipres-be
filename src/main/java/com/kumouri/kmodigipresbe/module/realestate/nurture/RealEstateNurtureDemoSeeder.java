package com.kumouri.kmodigipresbe.module.realestate.nurture;

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
 * T1 (RE Database Goldmine) — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the {@code DataSeeder} precedent) that stands up a fictional
 * brokerage tenant <strong>"Gateway Realty"</strong> with a <strong>dormant-lead database</strong> + the
 * RE nurture campaign, so the demo moment runs end-to-end on fictional data.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-realestate")}, so it is absent from dev / CI / prod default runs (it only
 * activates when {@code SPRING_PROFILES_ACTIVE} includes {@code demo-realestate}). Idempotent on the
 * tenant slug {@code gateway-realty} — re-running does nothing.
 *
 * <h2>What it seeds</h2>
 * <ul>
 *   <li>Tenant "Gateway Realty" (Dallas-tilt brand) + an ADMIN user, modules {@code realestate} +
 *       {@code nurture} + {@code responder} enabled, a non-zero {@code aiBudgetUsd} (so AI-personalized
 *       cadence copy can run).</li>
 *   <li>An {@code IntegrationConnection(twilio)} carrying {@code config.bookingLink} (the reply→book SMS
 *       target), {@code config.notifyPhone}/{@code notifyEmail} (the agent) and a sandbox {@code authToken}.
 *       {@code smsMode} is <strong>unset</strong> — so an inbound positive reply falls through to the E2
 *       responder seam + {@code RealEstateNurtureReplyHandler} (not the grounded concierge).</li>
 *   <li>A {@code ResponderConfig(vertical="realestate", enabled, intents=[POSITIVE_REPLY, ...])} so the
 *       reply→book handler activates.</li>
 *   <li>A <strong>dormant-lead DB</strong>: ~12 contacts spread across the A/B/C/D recency bands (some
 *       with WON deals to land in the high-value A tier; two opted-out to show the TCPA skip), each with a
 *       backdated {@code Activity} so segmentation buckets them deterministically.</li>
 *   <li>The seeded RE nurture campaign (the A/B/C/D segment thresholds + the SMS/email cadence).</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-realestate,dev}) "Gateway Realty has 12 leads that went
 *       cold months ago. Watch the system wake them up — safely."</li>
 *   <li>{@code POST /api/v1/realestate/nurture/campaigns/{id}/segment-and-enroll} → "One click segments
 *       them: hot-dormant high-value (A), warm (B), cold (C), deep (D). The two opted-out leads were
 *       skipped automatically — TCPA-safe."</li>
 *   <li>Flip the runner on ({@code kmosf.modules.nurture-runner.enabled=true}) / trigger a sweep → "It
 *       texts each tier on its own cadence. Every message — even AI-personalized — passes a Fair-Housing
 *       lint first. Here's one the AI tried to write with 'great for families' — caught, compliant
 *       version sent instead."</li>
 *   <li>Reply "YES" as a buyer (inbound SMS) → "A positive reply instantly exits the cadence and texts a
 *       booking link — the lead is re-engaged and booking a showing, hands-free."</li>
 *   <li>{@code GET /api/v1/realestate/nurture/campaigns/{id}/analytics} → "The agent sees which dormant
 *       tier is converting — enrolled / replied / booked per bucket. The database goldmine, mined."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-realestate")
public class RealEstateNurtureDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "gateway-realty";
    public static final String CAMPAIGN_NAME = "Dormant Lead Reactivation";
    private static final String AGENT_EMAIL = "agent@gateway-realty.example";
    private static final String NOTIFY_PHONE = "+12145550100";
    private static final String NOTIFY_EMAIL = "agent@gateway-realty.example";
    private static final String BOOKING_LINK = "https://cal.example/gateway-realty/showing";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ContactRepository contacts;
    private final ActivityRepository activities;
    private final DealRepository deals;
    private final NurtureCampaignRepository campaigns;
    private final ResponderConfigRepository responderConfigs;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;
    private final Clock clock;

    public RealEstateNurtureDemoSeeder(TenantRepository tenants,
                                       UserRepository users,
                                       ContactRepository contacts,
                                       ActivityRepository activities,
                                       DealRepository deals,
                                       NurtureCampaignRepository campaigns,
                                       ResponderConfigRepository responderConfigs,
                                       IntegrationConnectionRepository connections,
                                       PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.contacts = contacts;
        this.activities = activities;
        this.deals = deals;
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
                err -> log.error("RealEstateNurtureDemoSeeder failed", err));
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
                .displayName("Gateway Realty")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        RealEstateAutoConfigurationKeys.REALESTATE,
                        RealEstateAutoConfigurationKeys.NURTURE,
                        RealEstateAutoConfigurationKeys.RESPONDER))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Gateway Realty) + dormant-lead DB + RE nurture campaign",
                TENANT_SLUG);

        // All writes run under the tenant context (tenant-scoped repos auto-stamp/filter).
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(responderConfigs.save(responderConfig(tenantId)))
                        .then(campaigns.save(campaign(tenantId)))
                        .then(seedDormantLeads(tenantId))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — run segment-and-enroll, then the "
                        + "default-OFF nurture runner, to see T1 end-to-end", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(AGENT_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("gateway-demo-password"))
                .displayName("Gateway Realty Agent")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: bookingLink (reply→book target) + notify targets + a sandbox authToken; no smsMode. */
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

    /** Responder config (vertical=realestate) so the reply→book handler activates. */
    private ResponderConfig responderConfig(UUID tenantId) {
        return ResponderConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .enabled(true)
                .vertical(RealEstateNurtureReplyHandler.VERTICAL)
                .intents(List.of(
                        new IntentDefinition("POSITIVE_REPLY",
                                "The contact is interested / wants to re-engage / says yes to a chat or "
                                        + "a showing."),
                        new IntentDefinition("NOT_INTERESTED",
                                "The contact declines or is not interested right now.")))
                .replyCapPerContactPerDay(5)
                .build();
    }

    /** The RE nurture campaign — A/B/C/D dormancy segments + the SMS/email cadence (fair-housing-safe). */
    private NurtureCampaign campaign(UUID tenantId) {
        return NurtureCampaign.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .name(CAMPAIGN_NAME)
                .description("Reactivate dormant real-estate leads with tiered, fair-housing-safe cadences.")
                // GATE-2: tag the vertical so the composer dispatches the Fair-Housing copy filter.
                .vertical(FairHousingCopyFilter.VERTICAL)
                .active(true)
                .segments(List.of(
                        // A — hot-dormant + high value (30-90d dormant, >= $300k lifetime WON value).
                        new NurtureSegmentDefinition(DormancyBucket.A, 30, 90,
                                new BigDecimal("300000"), null),
                        // B — warm-dormant (90-180d).
                        new NurtureSegmentDefinition(DormancyBucket.B, 90, 180, null, null),
                        // C — cold-dormant (180-365d).
                        new NurtureSegmentDefinition(DormancyBucket.C, 180, 365, null, null),
                        // D — deep-dormant (365d+, open-ended).
                        new NurtureSegmentDefinition(DormancyBucket.D, 365, null, null, null)))
                .steps(List.of(
                        new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                                "Hi {firstName}, it's Gateway Realty — the market's moved since we last "
                                        + "talked. Want a quick no-pressure update on your home's value?",
                                null, null, true, 0),
                        new NurtureCadenceStep(1, NurtureChannel.EMAIL, 3,
                                "Your neighborhood market update, {firstName}",
                                "Your neighborhood market update, {firstName}",
                                "Hi {firstName}, a lot has changed in your area lately. Reply and we'll "
                                        + "put together a quick, no-obligation update on what your home "
                                        + "could sell for today.",
                                false, 2),
                        new NurtureCadenceStep(2, NurtureChannel.SMS, 7,
                                "Still here whenever you're ready, {firstName} — reply YES and I'll send "
                                        + "a time to chat.",
                                null, null, false, 4)))
                .maxTouchesPerContactPerWindow(3)
                .build();
    }

    /** ~12 dormant leads across the A/B/C/D bands; 2 opted-out; A-tier leads get a WON deal for value. */
    private Mono<Void> seedDormantLeads(UUID tenantId) {
        Instant now = clock.instant();
        List<Mono<?>> writes = new ArrayList<>();

        // Bucket A (30-90d dormant + high value): 3 leads, each with a WON deal >= $300k.
        writes.add(dormantLead(tenantId, "Ava", "Nguyen", "+12145551001", 45, now, false,
                new BigDecimal("420000")));
        writes.add(dormantLead(tenantId, "Marcus", "Bell", "+12145551002", 60, now, false,
                new BigDecimal("355000")));
        writes.add(dormantLead(tenantId, "Priya", "Shah", "+12145551003", 80, now, false,
                new BigDecimal("510000")));

        // Bucket B (90-180d): 4 leads (one opted-out to show the TCPA skip), no value band.
        writes.add(dormantLead(tenantId, "Daniel", "Cruz", "+12145551004", 100, now, false, null));
        writes.add(dormantLead(tenantId, "Sofia", "Reyes", "+12145551005", 130, now, false, null));
        writes.add(dormantLead(tenantId, "Liam", "Walsh", "+12145551006", 150, now, true, null));
        writes.add(dormantLead(tenantId, "Grace", "Kim", "+12145551007", 175, now, false, null));

        // Bucket C (180-365d): 3 leads (one opted-out).
        writes.add(dormantLead(tenantId, "Noah", "Patel", "+12145551008", 200, now, false, null));
        writes.add(dormantLead(tenantId, "Emma", "Diaz", "+12145551009", 300, now, true, null));
        writes.add(dormantLead(tenantId, "Owen", "Brooks", "+12145551010", 360, now, false, null));

        // Bucket D (365d+): 2 leads.
        writes.add(dormantLead(tenantId, "Maya", "Flores", "+12145551011", 400, now, false, null));
        writes.add(dormantLead(tenantId, "Ethan", "Ward", "+12145551012", 540, now, false, null));

        return Mono.when(writes);
    }

    /**
     * Seed one dormant lead: a Contact (optionally opted-out via the {@code sms-opt-out} tag), a backdated
     * {@code Activity(CONTACT)} {@code daysDormant} days ago (so segmentation buckets it), and — when
     * {@code wonValue != null} — a WON {@code Deal} so the value-band A tier can match.
     */
    private Mono<?> dormantLead(UUID tenantId, String first, String last, String phone, int daysDormant,
                                Instant now, boolean optedOut, BigDecimal wonValue) {
        UUID contactId = UUID.randomUUID();
        Instant lastSeen = now.minus(daysDormant, ChronoUnit.DAYS);
        Set<String> tags = optedOut
                ? Set.of("realestate-lead", RiskTieredPreventionService.SMS_OPT_OUT_TAG)
                : Set.of("realestate-lead");
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
                .summary("Last contact — buyer/seller consultation")
                .occurredAt(lastSeen)
                .build();

        Mono<?> base = contacts.save(contact).then(activities.save(activity));
        if (wonValue == null) {
            return base;
        }
        Deal won = Deal.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .title("Prior sale — " + first + " " + last)
                .stage(PipelineStage.WON)
                .value(wonValue)
                .currency("USD")
                .primaryContactId(contactId)
                .stageChangedAt(lastSeen)
                .build();
        return base.then(deals.save(won));
    }

    /** Module keys used to seed {@code Tenant.enabledModules} (kept local to avoid a cross-module import knot). */
    private static final class RealEstateAutoConfigurationKeys {
        static final String REALESTATE = "realestate";
        static final String NURTURE = "nurture";
        static final String RESPONDER = "responder";
    }
}
