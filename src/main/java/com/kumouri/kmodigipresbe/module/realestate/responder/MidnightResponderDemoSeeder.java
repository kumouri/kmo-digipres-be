package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.InboundSmsService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.model.DisclosureType;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosure;
import com.kumouri.kmodigipresbe.module.realestate.nurture.RealEstateNurtureReplyHandler;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingDisclosureService;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — the demo seed for the 60-second "watch this". A
 * {@code @Profile}-gated, idempotent {@code CommandLineRunner} (the {@code RealEstateNurtureDemoSeeder}
 * precedent) that stands up a fictional brokerage tenant <strong>"Gateway Realty (Midnight)"</strong> wired
 * for the full Midnight-Responder loop: a grounded listing + the tier→campaign routing + the off-listing
 * handoff + the latency stat.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-realestate-responder")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code gateway-realty-midnight} — re-running does nothing.
 *
 * <h2>What it seeds</h2>
 * <ul>
 *   <li>Tenant "Gateway Realty (Midnight)" + an ADMIN user; modules {@code realestate} + {@code responder} +
 *       {@code nurture} enabled; a non-zero {@code aiBudgetUsd} (so the grounded-answer + cadence AI can run).</li>
 *   <li>An {@code IntegrationConnection(twilio)} with {@code smsMode="realestate"} (so an inbound text goes
 *       to the grounded concierge), {@code config.notifyPhone}/{@code notifyEmail} (the agent), and a
 *       sandbox {@code authToken}.</li>
 *   <li>A {@link Listing} (the tracked number buyers text) + two {@link ListingDisclosure}s indexed via
 *       {@link ListingDisclosureService} (so "text in → cited answer" works on fictional data).</li>
 *   <li>A {@code ResponderConfig(vertical="realestate", enabled, intents=[...])} so the off-listing E2
 *       default-handoff (and any RE-nurture reply→book) activates.</li>
 *   <li>A <strong>WARM</strong> nurture campaign + a <strong>COLD</strong> (6-month long-cadence) campaign.</li>
 *   <li>A {@link MidnightResponderConfig} mapping warm→WARM-campaign, cold→COLD-campaign,
 *       {@code delegateHandoffToResponder=true}, after-hours window 8..18.</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-realestate-responder,dev}) "It's midnight. A buyer texts
 *       the sign on a listing. No agent is awake."</li>
 *   <li>Text a disclosure question to the listing's tracked number → a cited answer in seconds (RE-1).</li>
 *   <li>Qualify HOT (reveal a strong budget + ASAP) → the RE-2 hot-handoff alerts the agent + a showing
 *       offer goes out (the existing pieces, verified).</li>
 *   <li>Qualify WARM → "Not ready to buy today? The system quietly drops them into a nurture cadence — no
 *       lead lost." (the new tier routing — the buyer is auto-enrolled in the WARM campaign).</li>
 *   <li>Text an off-listing / unanswerable question → "It doesn't guess. It hands off to a human and texts
 *       the buyer back — never a dead end." (the E2 responder handoff).</li>
 *   <li>{@code GET /api/v1/realestate/responder/latency-stats} → "Median reply under 30 seconds, and 100%
 *       of these came in after hours. The front desk that never sleeps."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-realestate-responder")
public class MidnightResponderDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "gateway-realty-midnight";
    public static final String WARM_CAMPAIGN_NAME = "Midnight — Warm Lead Nurture";
    public static final String COLD_CAMPAIGN_NAME = "Midnight — Cold Lead Long-Cadence";
    private static final String AGENT_EMAIL = "agent@gateway-realty-midnight.example";
    private static final String NOTIFY_PHONE = "+12145550200";
    private static final String NOTIFY_EMAIL = "agent@gateway-realty-midnight.example";
    private static final String TRACKED_PHONE = "+12145550199";
    private static final String BOOKING_LINK = "https://cal.example/gateway-realty-midnight/showing";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ListingService listingService;
    private final ListingDisclosureService disclosureService;
    private final NurtureCampaignRepository campaigns;
    private final ResponderConfigRepository responderConfigs;
    private final MidnightResponderConfigRepository midnightConfigs;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public MidnightResponderDemoSeeder(TenantRepository tenants,
                                       UserRepository users,
                                       ListingService listingService,
                                       ListingDisclosureService disclosureService,
                                       NurtureCampaignRepository campaigns,
                                       ResponderConfigRepository responderConfigs,
                                       MidnightResponderConfigRepository midnightConfigs,
                                       IntegrationConnectionRepository connections,
                                       PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.listingService = listingService;
        this.disclosureService = disclosureService;
        this.campaigns = campaigns;
        this.responderConfigs = responderConfigs;
        this.midnightConfigs = midnightConfigs;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("MidnightResponderDemoSeeder failed", err));
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
                .displayName("Gateway Realty (Midnight)")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        RealEstateAutoConfiguration.MODULE_KEY,
                        ResponderAutoConfiguration.MODULE_KEY,
                        "nurture"))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Gateway Realty Midnight) + listing + warm/cold campaigns + config",
                TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        UUID warmCampaignId = UUID.randomUUID();
        UUID coldCampaignId = UUID.randomUUID();
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(responderConfigs.save(responderConfig(tenantId)))
                        .then(campaigns.save(warmCampaign(tenantId, warmCampaignId)))
                        .then(campaigns.save(coldCampaign(tenantId, coldCampaignId)))
                        .then(midnightConfigs.save(midnightConfig(tenantId, warmCampaignId, coldCampaignId)))
                        .then(seedListingWithDisclosures(tenantId).contextWrite(TenantContextHolder.write(ctx)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — text the tracked number {} to see the "
                        + "Midnight Responder loop end-to-end", TENANT_SLUG, TRACKED_PHONE));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(AGENT_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("midnight-demo-password"))
                .displayName("Gateway Realty Agent")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: realestate SMS mode (inbound → grounded concierge) + notify targets + sandbox token. */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        Map<String, String> config = new HashMap<>();
        config.put(InboundSmsService.SMS_MODE_KEY, InboundSmsService.SMS_MODE_REALESTATE);
        config.put("notifyPhone", NOTIFY_PHONE);
        config.put("notifyEmail", NOTIFY_EMAIL);
        config.put("bookingLink", BOOKING_LINK);
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "demo-sandbox-authtoken",
                        "fromNumber", TRACKED_PHONE)))
                .config(config)
                .build();
    }

    /** Responder config (vertical=realestate) so the off-listing E2 default-handoff + reply→book activate. */
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
                        new IntentDefinition("GENERAL_QUESTION",
                                "A general buyer question not tied to a specific listing.")))
                .replyCapPerContactPerDay(5)
                .build();
    }

    /** Listing buyers text (the tracked number) + two indexed disclosures so the cited-answer path works. */
    private Mono<Void> seedListingWithDisclosures(UUID tenantId) {
        return listingService.create(Listing.builder()
                        .addressLine("742 Magnolia Ave").city("Dallas").state("TX").zip("75204")
                        .price(new BigDecimal("465000")).beds(3).baths(new BigDecimal("2"))
                        .sqft(1850).trackedPhone(TRACKED_PHONE)
                        .build())
                .flatMap(listing -> disclosureService.create(listing.getId(), ListingDisclosure.builder()
                                .disclosureType(DisclosureType.ROOF)
                                .text("Roof replaced in 2021 with architectural shingles; a transferable "
                                        + "25-year manufacturer warranty is on file.")
                                .build())
                        .then(disclosureService.create(listing.getId(), ListingDisclosure.builder()
                                .disclosureType(DisclosureType.HOA)
                                .text("HOA dues are $145/month and cover front-yard landscaping and the "
                                        + "community pool; no special assessments are pending.")
                                .build())))
                .then();
    }

    /** WARM campaign — moderately dormant tier, a short re-engage cadence (fair-housing-safe copy). */
    private NurtureCampaign warmCampaign(UUID tenantId, UUID id) {
        return NurtureCampaign.builder()
                .id(id)
                .tenantId(tenantId)
                .name(WARM_CAMPAIGN_NAME)
                .description("Re-engage warm buyer leads who qualified but aren't ready to buy today.")
                .active(true)
                .segments(List.of(
                        new NurtureSegmentDefinition(DormancyBucket.B, 0, null, null, null)))
                .steps(List.of(
                        new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                                "Hi {firstName}, it's Gateway Realty — great chatting earlier! Want me to "
                                        + "send a few listings that match what you're looking for?",
                                null, null, true, 0),
                        new NurtureCadenceStep(1, NurtureChannel.EMAIL, 3,
                                "A quick market update, {firstName}",
                                "A quick market update, {firstName}",
                                "Hi {firstName}, here's a no-pressure update on what's moving in your price "
                                        + "range. Reply any time and we'll set up a tour.",
                                false, 2)))
                .maxTouchesPerContactPerWindow(3)
                .build();
    }

    /** COLD campaign — deep-dormant tier, a 6-month long-cadence drip (fair-housing-safe copy). */
    private NurtureCampaign coldCampaign(UUID tenantId, UUID id) {
        return NurtureCampaign.builder()
                .id(id)
                .tenantId(tenantId)
                .name(COLD_CAMPAIGN_NAME)
                .description("Long-cadence (6-month) drip for cold leads — stay top-of-mind without pressure.")
                .active(true)
                .segments(List.of(
                        new NurtureSegmentDefinition(DormancyBucket.C, 0, null, null, null)))
                .steps(List.of(
                        new NurtureCadenceStep(0, NurtureChannel.EMAIL, 0,
                                "Whenever you're ready, {firstName}",
                                "Whenever you're ready, {firstName}",
                                "Hi {firstName}, no rush at all — I'll check in occasionally with market "
                                        + "updates. Reply STOP any time to opt out.",
                                false, 0),
                        new NurtureCadenceStep(1, NurtureChannel.EMAIL, 60,
                                "Your market, 2 months on, {firstName}",
                                "Your market, 2 months on, {firstName}",
                                "Hi {firstName}, a quick seasonal update on your area. Still here whenever "
                                        + "the time is right.",
                                false, 60),
                        new NurtureCadenceStep(2, NurtureChannel.SMS, 90,
                                "Hi {firstName}, it's Gateway Realty checking in — reply YES if you'd like a "
                                        + "fresh look at the market.",
                                null, null, false, 90)))
                .maxTouchesPerContactPerWindow(2)
                .build();
    }

    /** The Midnight Responder config: tier→campaign mapping + handoff delegation on + after-hours 8..18. */
    private MidnightResponderConfig midnightConfig(UUID tenantId, UUID warmCampaignId, UUID coldCampaignId) {
        return MidnightResponderConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .warmCampaignId(warmCampaignId)
                .coldCampaignId(coldCampaignId)
                .delegateHandoffToResponder(true)
                .afterHoursStartHour(MidnightResponderConfig.DEFAULT_AFTER_HOURS_START)
                .afterHoursEndHour(MidnightResponderConfig.DEFAULT_AFTER_HOURS_END)
                .build();
    }
}
