package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T11 (Home "QuoteCloser") — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the {@code QuoteNowDemoSeeder} / {@code RealEstateNurtureDemoSeeder}
 * precedent) that stands up a fictional HVAC home-services tenant <strong>"Comfort Air HVAC
 * (QuoteCloser)"</strong> wired for the un-accepted-quote nurture + won-job review composition: the
 * {@code quoting} + {@code nurture} modules, a Twilio {@link IntegrationConnection} (a sandbox
 * {@code bookingLink} + {@code reviewLink} + notify targets), a QuoteCloser {@code NurtureCampaign} (tagged
 * {@code vertical="home"} ⇒ the cadence copy is sent UNFILTERED), a {@link QuoteCloserConfig} with a short
 * window, and ONE aged {@code NEW} (un-accepted) {@code QuoteRequest} so the 60-sec runs.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-home-quote-closer")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code comfort-air-quotecloser} — re-running does nothing. All data is
 * fictional. Going live needs A2P 10DLC for the cadence + review-request SMS (a separate human action).
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-home-quote-closer,dev}, with
 *       {@code KMOSF_MODULES_QUOTE_CLOSER_JOB_ENABLED=true} + {@code KMOSF_MODULES_NURTURE_RUNNER_ENABLED=true})
 *       "Comfort Air sent a homeowner an instant quote a few days ago — and never heard back."</li>
 *   <li>The default-OFF {@code QuoteCloserEnrollmentJob} sweep ages the {@code NEW} quote past the window →
 *       enrolls the homeowner in the QuoteCloser cadence. The {@code NurtureRunner} texts the reminder, then
 *       the financing-nudge, then the last-call — "automated follow-up that closes un-accepted quotes."</li>
 *   <li>The homeowner accepts (POST the T8 accept endpoint, or set the quote ACCEPTED) → the
 *       {@code QuoteWonSubscriber} <strong>stops the cadence</strong> (no more nudges) and fires <strong>one
 *       review request</strong> for the won job — "the moment they say yes, the chasing stops and the ask
 *       for a Google review goes out."</li>
 *   <li>{@code GET /api/v1/quoting/quote-closer/analytics} → "the recovery funnel: quotes sent → followed-up
 *       → recovered → review-requested, with the recovery rate."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-home-quote-closer")
public class QuoteCloserDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "comfort-air-quotecloser";
    public static final String CAMPAIGN_NAME = "QuoteCloser — un-accepted quote follow-up";
    /** vertical="home" — home has NO registered nurture copy filter ⇒ the cadence copy is sent unfiltered. */
    public static final String HOME_VERTICAL = "home";

    private static final String ADMIN_EMAIL = "office@comfort-air-quotecloser.example";
    private static final String NOTIFY_PHONE = "+13145550800";
    private static final String NOTIFY_EMAIL = "office@comfort-air-quotecloser.example";
    private static final String TRACKED_PHONE = "+13145550799";
    private static final String HOMEOWNER_PHONE = "+13145550810";
    private static final String BOOKING_LINK = "https://comfort-air-quotecloser.example/book";
    private static final String REVIEW_LINK = "https://g.page/comfort-air-quotecloser/review";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ContactRepository contacts;
    private final QuoteRequestRepository quotes;
    private final NurtureCampaignRepository campaigns;
    private final QuoteCloserConfigRepository closerConfigs;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public QuoteCloserDemoSeeder(TenantRepository tenants,
                                 UserRepository users,
                                 ContactRepository contacts,
                                 QuoteRequestRepository quotes,
                                 NurtureCampaignRepository campaigns,
                                 QuoteCloserConfigRepository closerConfigs,
                                 IntegrationConnectionRepository connections,
                                 PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.contacts = contacts;
        this.quotes = quotes;
        this.campaigns = campaigns;
        this.closerConfigs = closerConfigs;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("QuoteCloserDemoSeeder failed", err));
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
                .displayName("Comfort Air HVAC (QuoteCloser)")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        QuotingAutoConfiguration.MODULE_KEY,
                        NurtureAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Comfort Air HVAC QuoteCloser) + QuoteCloser campaign + config "
                + "+ one aged un-accepted quote", TENANT_SLUG);

        UUID campaignId = UUID.randomUUID();
        UUID homeownerId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(campaigns.save(campaign(tenantId, campaignId)))
                        .then(closerConfigs.save(closerConfig(tenantId, campaignId)))
                        .then(contacts.save(homeowner(tenantId, homeownerId)))
                        .then(quotes.save(agedNewQuote(tenantId, homeownerId)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — opt in the QuoteCloser job + the nurture "
                        + "runner, run a sweep to enroll the aged quote, then accept it to see the cadence "
                        + "stop + a review request fire; GET /api/v1/quoting/quote-closer/analytics for the "
                        + "recovery funnel", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("quotecloser-demo-password"))
                .displayName("Comfort Air Office")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: the booking link the T8 accept texts + the E3 review link the sender uses + notify. */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        Map<String, String> config = new HashMap<>();
        config.put("notifyPhone", NOTIFY_PHONE);
        config.put("notifyEmail", NOTIFY_EMAIL);
        config.put("bookingLink", BOOKING_LINK);
        config.put("reviewLink", REVIEW_LINK);
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "demo-sandbox-authtoken",
                        "fromNumber", TRACKED_PHONE)))
                .config(config)
                .build();
    }

    /**
     * The QuoteCloser nurture campaign — vertical="home" (UNFILTERED copy), a single open dormancy segment,
     * and the reminder → financing-nudge → last-call cadence (SMS/email). Short offsets for the demo.
     */
    private NurtureCampaign campaign(UUID tenantId, UUID campaignId) {
        return NurtureCampaign.builder()
                .id(campaignId)
                .tenantId(tenantId)
                .name(CAMPAIGN_NAME)
                .description("Follow up on instant quotes the homeowner hasn't accepted yet.")
                // GATE-2: home has NO registered copy filter ⇒ the cadence copy is sent unfiltered (correct).
                .vertical(HOME_VERTICAL)
                .active(true)
                // One open segment — the QuoteCloser enrolls a specific quote's contact directly (the job
                // does NOT run segmentation; this segment exists only so the campaign is admin-segmentable).
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.A, 0, null, null, null)))
                .steps(List.of(
                        new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                                "Hi {firstName}, it's Comfort Air HVAC — just checking in on the estimate we "
                                        + "sent. Happy to answer any questions or get you on the schedule.",
                                null, null, false, 0),
                        new NurtureCadenceStep(1, NurtureChannel.SMS, 2,
                                "Hi {firstName} — a quick note: we offer financing on replacements, so a new "
                                        + "system may be more affordable than you'd expect. Want the details?",
                                null, null, false, 0),
                        new NurtureCadenceStep(2, NurtureChannel.EMAIL, 3,
                                "Your Comfort Air estimate — still good, {firstName}",
                                "Your Comfort Air estimate — still good, {firstName}",
                                "Hi {firstName}, your estimate is still good and we'd love to help. Reply to "
                                        + "this email or call us and we'll get you booked at a time that works.",
                                false, 0)))
                .maxTouchesPerContactPerWindow(5)
                .build();
    }

    /**
     * The per-tenant QuoteCloser config: the campaign + a <strong>0-hour window</strong> for the demo, so the
     * first sweep enrolls the freshly-seeded quote immediately (a real tenant uses 24h+). A 0 window avoids
     * needing to backdate the quote's {@code @CreatedDate}-managed {@code createdAt} (which Spring Data
     * auditing stamps to "now" on insert).
     */
    private QuoteCloserConfig closerConfig(UUID tenantId, UUID campaignId) {
        return QuoteCloserConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .campaignId(campaignId)
                .unacceptedWindowHours(0)
                .build();
    }

    /** The homeowner who got a quote and went quiet. */
    private Contact homeowner(UUID tenantId, UUID contactId) {
        return Contact.builder()
                .id(contactId)
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .firstName("Jordan")
                .displayName("Jordan Ellis")
                .phones(List.of(PhoneNumber.builder().number(HOMEOWNER_PHONE).label("mobile").build()))
                .emails(List.of(new EmailContact("jordan.ellis@example.com")))
                .tags(Set.of("quoting-lead"))
                .build();
    }

    /**
     * One {@code NEW} (un-accepted) quote. With the demo's 0-hour window the first sweep enrolls it
     * immediately — so no need to backdate {@code createdAt} (Spring Data auditing stamps it to "now" on
     * insert anyway). The range/recommendation are left minimal — the QuoteCloser only needs status +
     * contactId + createdAt.
     */
    private QuoteRequest agedNewQuote(UUID tenantId, UUID contactId) {
        return QuoteRequest.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(contactId)
                .contactPhone(HOMEOWNER_PHONE)
                .contactEmail("jordan.ellis@example.com")
                .problemDescription("12-year-old AC blowing warm — got an estimate, thinking it over.")
                .status(QuoteStatus.NEW)
                .build();
    }
}
