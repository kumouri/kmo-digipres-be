package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.integration.twilio.voice.extract.MultiTradeExtractionStrategy;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T5 (Home Services "Instant Callback") — the demo seed for the 60-second "watch this". A
 * {@code @Profile}-gated, idempotent {@code CommandLineRunner} (the {@code SwitchboardDemoSeeder} /
 * {@code MidnightResponderDemoSeeder} precedent) that stands up a fictional home-services tenant
 * <strong>"Reliable Home Services"</strong> wired for the full callback loop: home-services + responder
 * enabled, a home {@link ResponderConfig} (the callback intents + classifier prompt), a
 * {@link CallbackConfig} copy book, and two pre-seeded {@link CallbackRequest}s (a big-ticket HVAC
 * emergency + a small routine job) so the revenue-ranked queue is visibly sorted on first load.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-home-callback")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code reliable-home-services} — re-running does nothing. All data is
 * fictional. The caller-facing offer SMS is still default-OFF unless
 * {@code kmosf.modules.home-callback-offer.enabled=true} is set (the demo enables it via the profile's
 * properties); a real callback SMS needs A2P 10DLC at go-live (a separate human action).
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-home-callback,dev}) "It's after hours. A customer calls
 *       the contractor, gets voicemail."</li>
 *   <li>The voicemail lands → the caller instantly gets a text: "Sorry we missed you! Reply NOW for a
 *       callback, or text a time." (the opt-in offer — the default-OFF subscriber, on for the demo).</li>
 *   <li>The caller replies "in 30 min" → a <strong>revenue-ranked callback card</strong> appears for the
 *       dispatcher with the AI summary, urgency, and $-band — the big-ticket HVAC emergency sorted to the
 *       top over a small routine job ({@code GET /api/v1/home-services/callbacks}).</li>
 *   <li>{@code GET /api/v1/home-services/callbacks/recovery-stats} → "3 missed calls, 3 offered, 2
 *       accepted, 1 dispatched — the front desk that never sleeps, now closes the loop."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-home-callback")
public class CallbackDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "reliable-home-services";
    private static final String ADMIN_EMAIL = "dispatch@reliable-home-services.example";
    private static final String NOTIFY_PHONE = "+13145550400";
    private static final String NOTIFY_EMAIL = "dispatch@reliable-home-services.example";
    private static final String TRACKED_PHONE = "+13145550399";
    private static final String CALLER_HVAC = "+13145550501";
    private static final String CALLER_ROUTINE = "+13145550502";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ResponderConfigRepository responderConfigs;
    private final CallbackConfigRepository callbackConfigs;
    private final CallbackRequestRepository callbackRequests;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public CallbackDemoSeeder(TenantRepository tenants,
                              UserRepository users,
                              ResponderConfigRepository responderConfigs,
                              CallbackConfigRepository callbackConfigs,
                              CallbackRequestRepository callbackRequests,
                              IntegrationConnectionRepository connections,
                              PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.responderConfigs = responderConfigs;
        this.callbackConfigs = callbackConfigs;
        this.callbackRequests = callbackRequests;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("CallbackDemoSeeder failed", err));
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
                .displayName("Reliable Home Services")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        HomeServicesAutoConfiguration.MODULE_KEY,
                        ResponderAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Reliable Home Services) + home responder config + callback "
                + "copy book + 2 ranked callback cards", TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(responderConfigs.save(responderConfig(tenantId)))
                        .then(callbackConfigs.save(callbackConfig(tenantId)))
                        .then(callbackRequests.save(hvacEmergencyCard(tenantId)))
                        .then(callbackRequests.save(routineCard(tenantId)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — text the tracked number {} to see the "
                        + "callback loop (try \"in 30 min\"); GET /api/v1/home-services/callbacks shows the "
                        + "revenue-ranked queue (HVAC emergency above the routine job)",
                        TENANT_SLUG, TRACKED_PHONE));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("callback-demo-password"))
                .displayName("Reliable Dispatch")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /**
     * Twilio connection: {@code voicemailVertical="home-services"} (the voicemail intake creates a DRAFT
     * WorkOrder), {@code smsMode} UNSET (an inbound reply falls through to the E2 responder router), notify
     * targets + a sandbox token.
     */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        Map<String, String> config = new HashMap<>();
        config.put("notifyPhone", NOTIFY_PHONE);
        config.put("notifyEmail", NOTIFY_EMAIL);
        config.put("voicemailVertical", MultiTradeExtractionStrategy.VERTICAL_KEY);
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider(TwilioSmsService.PROVIDER)
                .secrets(new HashMap<>(Map.of("authToken", "demo-sandbox-authtoken",
                        "fromNumber", TRACKED_PHONE)))
                .config(config)
                .build();
    }

    /** The home responder config: vertical=home, the callback intents, the callback classifier prompt. */
    private ResponderConfig responderConfig(UUID tenantId) {
        return ResponderConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .enabled(true)
                .vertical(CallbackIntents.VERTICAL)
                .intents(CallbackIntents.DEFAULTS)
                .systemPromptOverride(CallbackIntents.HOME_CALLBACK_CLASSIFIER_PROMPT)
                .replyCapPerContactPerDay(8)
                .build();
    }

    /** The callback copy book — fictional, friendly copy. */
    private CallbackConfig callbackConfig(UUID tenantId) {
        return CallbackConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .offerMessage("Sorry we missed your call at Reliable Home Services! Reply NOW for a "
                        + "callback as soon as we're free, or text a time (e.g. \"in 30 min\") and we'll "
                        + "call you then.")
                .immediateConfirmMessage("Got it — we'll call you right back. Thanks for your patience!")
                .scheduledConfirmMessage("Perfect — we'll call you at the time you asked. Talk soon!")
                .build();
    }

    /** A big-ticket HVAC emergency card — should sort to the TOP of the revenue-ranked queue. */
    private CallbackRequest hvacEmergencyCard(UUID tenantId) {
        Instant now = Instant.now();
        return CallbackRequest.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .fromPhone(CALLER_HVAC)
                .callSid("DEMOCALLSID-HVAC-EMERGENCY")
                .mode(CallbackMode.IMMEDIATE)
                .status(CallbackStatus.REQUESTED)
                .summaryLine("Voicemail lead from Dana: no heat, furnace clicking (HVAC, urgency: "
                        + "EMERGENCY) — callback requested")
                .urgency("EMERGENCY")
                .jobValueBand("LARGE")
                .revenueScore(CallbackRevenueRanker.score("LARGE", "EMERGENCY", now))
                .build();
    }

    /** A small routine card — should sort BELOW the HVAC emergency. */
    private CallbackRequest routineCard(UUID tenantId) {
        Instant now = Instant.now();
        return CallbackRequest.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .fromPhone(CALLER_ROUTINE)
                .callSid("DEMOCALLSID-ROUTINE")
                .mode(CallbackMode.SCHEDULED)
                .requestedWindowText("after 5pm")
                .status(CallbackStatus.REQUESTED)
                .summaryLine("Voicemail lead from Sam: leaky faucet, no rush (PLUMBING, urgency: ROUTINE)")
                .urgency("ROUTINE")
                .jobValueBand("SMALL")
                .revenueScore(CallbackRevenueRanker.score("SMALL", "ROUTINE", now))
                .build();
    }
}
