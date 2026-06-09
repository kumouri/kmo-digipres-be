package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T4 (Health "Switchboard AI") — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the {@code MidnightResponderDemoSeeder} precedent) that stands up a
 * fictional clinic tenant <strong>"Bright Smiles Family Dental (Switchboard)"</strong> wired for the full
 * Switchboard loop: frontdesk + responder enabled, a health {@link ResponderConfig} (the PHI-forbidding
 * classifier prompt), and a {@link SwitchboardConfig} logistics answer book.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-health-switchboard")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code bright-smiles-switchboard} — re-running does nothing. All data is
 * <strong>fictional</strong>; a live Switchboard on real patient data needs the separately-priced,
 * BAA-gated compliance tier (go-live notes).
 *
 * <h2>What it seeds</h2>
 * <ul>
 *   <li>Tenant "Bright Smiles Family Dental (Switchboard)" + an ADMIN user; modules {@code frontdesk} +
 *       {@code responder} enabled; a non-zero {@code aiBudgetUsd} (so the classifier can run).</li>
 *   <li>An {@code IntegrationConnection(twilio)} with notify targets + a sandbox {@code authToken};
 *       {@code smsMode} is left UNSET so an inbound text falls through to the E2 responder router (not the
 *       ChairFill / RealEstate seams).</li>
 *   <li>A {@code ResponderConfig(vertical="health", intents=SwitchboardIntents.DEFAULTS,
 *       systemPromptOverride=SwitchboardIntents.HEALTH_CLASSIFIER_PROMPT)} so the logistics + tripwire
 *       handlers activate and clinical messages are classified with empty slots.</li>
 *   <li>A {@link SwitchboardConfig} with fictional hours / location / booking / intake / review copy.</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-health-switchboard,dev}) "It's after hours at a busy
 *       dental office. Patients text the front desk. No one is at the desk."</li>
 *   <li>Text "what are your hours?" to the tracked number → an instant, accurate answer from the config —
 *       no human touched it (LOGISTICS).</li>
 *   <li>Text "I have chest pain" → an instant "a member of our care team will call you back… if this is an
 *       emergency call 911" — and the office gets a callback alert. <strong>Crucially, the stored
 *       conversation contains NONE of the patient's words</strong> — the AI never touches the chart
 *       (TRIPWIRE; PHI-free by construction).</li>
 *   <li>{@code GET /api/v1/frontdesk/switchboard/deflection-stats} → "Look: 8 routine questions answered,
 *       2 clinical messages safely escalated, 0 missed. The front desk that never sleeps — and never
 *       touches PHI."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-health-switchboard")
public class SwitchboardDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "bright-smiles-switchboard";
    private static final String ADMIN_EMAIL = "frontdesk@bright-smiles-switchboard.example";
    private static final String NOTIFY_PHONE = "+12145550300";
    private static final String NOTIFY_EMAIL = "frontdesk@bright-smiles-switchboard.example";
    private static final String TRACKED_PHONE = "+12145550299";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ResponderConfigRepository responderConfigs;
    private final SwitchboardConfigRepository switchboardConfigs;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public SwitchboardDemoSeeder(TenantRepository tenants,
                                 UserRepository users,
                                 ResponderConfigRepository responderConfigs,
                                 SwitchboardConfigRepository switchboardConfigs,
                                 IntegrationConnectionRepository connections,
                                 PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.responderConfigs = responderConfigs;
        this.switchboardConfigs = switchboardConfigs;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("SwitchboardDemoSeeder failed", err));
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
                .displayName("Bright Smiles Family Dental (Switchboard)")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        FrontDeskAutoConfiguration.MODULE_KEY,
                        ResponderAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Bright Smiles Switchboard) + health responder config + "
                + "logistics answer book", TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(responderConfigs.save(responderConfig(tenantId)))
                        .then(switchboardConfigs.save(switchboardConfig(tenantId)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — text the tracked number {} to see the "
                        + "Switchboard loop (try \"what are your hours?\" then \"I have chest pain\")",
                        TENANT_SLUG, TRACKED_PHONE));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("switchboard-demo-password"))
                .displayName("Bright Smiles Front Desk")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: smsMode UNSET (inbound → E2 responder) + notify targets + sandbox token. */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        Map<String, String> config = new HashMap<>();
        config.put("notifyPhone", NOTIFY_PHONE);
        config.put("notifyEmail", NOTIFY_EMAIL);
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
     * The health responder config: vertical=health, the full intent set, and the PHI-forbidding classifier
     * prompt (so a clinical message classifies as CLINICAL_SYMPTOM with empty slots).
     */
    private ResponderConfig responderConfig(UUID tenantId) {
        return ResponderConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .enabled(true)
                .vertical(SwitchboardIntents.VERTICAL)
                .intents(SwitchboardIntents.DEFAULTS)
                .systemPromptOverride(SwitchboardIntents.HEALTH_CLASSIFIER_PROMPT)
                .replyCapPerContactPerDay(8)
                .build();
    }

    /** The logistics answer book — fictional, PHI-free copy. */
    private SwitchboardConfig switchboardConfig(UUID tenantId) {
        return SwitchboardConfig.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .hoursText("Mon–Thu 8am–5pm, Fri 8am–2pm; closed weekends")
                .locationText("1420 Oak Lawn Ave, Suite 210, Dallas TX 75207; free parking in the rear lot")
                .acceptingNewPatients(true)
                .bookingInstructions("call us at (214) 555-0299 or book online at "
                        + "https://example.com/bright-smiles/book")
                .rescheduleInstructions("call us at (214) 555-0299 and we'll find a new time")
                .intakeFormUrl("https://example.com/bright-smiles/new-patient-forms")
                .reviewLinkUrl("https://example.com/bright-smiles/review")
                .safeTripwireReply(SwitchboardRedaction.DEFAULT_SAFE_TRIPWIRE_REPLY)
                .build();
    }
}
