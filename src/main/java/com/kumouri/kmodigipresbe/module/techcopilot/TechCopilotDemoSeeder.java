package com.kumouri.kmodigipresbe.module.techcopilot;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.techcopilot.model.EquipmentType;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechDoc;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechDocService;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
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
 * Tech Copilot (T13) — the demo seed for the 60-second "watch this". A {@code @Profile}-gated, idempotent
 * {@code CommandLineRunner} (the {@code MidnightResponderDemoSeeder} precedent) that stands up a fictional
 * HVAC tenant <strong>"Summit Mechanical"</strong> with the {@code techcopilot} module on, an Anthropic
 * connection, and a small seeded manual corpus — so "ask a manual question → a grounded, cited answer" and
 * "ask something not documented → an honest handoff" both work on fictional data.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-home-techcopilot")}; absent from dev / CI / prod default runs. Idempotent on
 * the tenant slug {@code summit-mechanical} — re-running does nothing.
 *
 * <h2>The 60-second "watch this" (the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-home-techcopilot,dev}) "A tech is on a roof with an old
 *       furnace throwing a fault code. The manual is in the truck."</li>
 *   <li>{@code POST /api/v1/techcopilot/ask {"question":"furnace fault E3 reset steps"}} → a grounded answer
 *       citing the seeded furnace manual (the exact reset procedure, with the source attributed).</li>
 *   <li>Ask something not in the corpus ({@code "how do I defrost a walk-in freezer?"}) → the no-context
 *       "I don't have that documented" handoff — never a fabricated procedure.</li>
 * </ol>
 *
 * <p>With no live OpenAI key the demo's retrieval works the same way the ITs do (the embedding is a mock /
 * the in-memory index + the {@code retrieveForCorpus} source-type filter return the seeded chunks); with a
 * real Anthropic key the grounded answer is the strict model call, otherwise the answer path hands off.
 */
@Slf4j
@Component
@Profile("demo-home-techcopilot")
public class TechCopilotDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "summit-mechanical";
    private static final String ADMIN_EMAIL = "dispatch@summit-mechanical.example";

    private static final String FURNACE_MANUAL_TITLE =
            "Summit GX9 Gas Furnace — Service & Fault-Code Manual";
    private static final String FURNACE_MANUAL_TEXT =
            "Summit GX9 Gas Furnace — Service Manual.\n\n"
            + "FAULT CODES (LED blink count on the control board):\n"
            + "Fault E1 — pressure switch stuck open. Check the vent for blockage and the condensate trap.\n"
            + "Fault E2 — ignition lockout after 3 failed trials. Check the igniter and gas valve.\n"
            + "Fault E3 — flame rollout / high-limit trip. The rollout switch has tripped due to overheating "
            + "or a blocked heat exchanger.\n"
            + "  RESET PROCEDURE for Fault E3: 1) Turn the furnace power off at the switch and wait 30 "
            + "seconds. 2) Inspect the burner area and heat exchanger for obstruction or soot and clear it. "
            + "3) Locate the manual-reset rollout switch on the burner bracket and press its red reset "
            + "button firmly until it clicks. 4) Restore power and start a heat cycle. If E3 returns, do NOT "
            + "keep resetting — the heat exchanger may be cracked; tag the unit out of service.\n"
            + "Fault E4 — flame sensed with no call for heat. Replace the gas valve.\n\n"
            + "ROUTINE SERVICE: replace the 16x25x1 filter every 90 days; the blower wheel set screw torque "
            + "is 50 in-lb.";

    private static final String WATER_HEATER_SPEC_TITLE =
            "Summit AquaMax 50 Water Heater — Spec Sheet";
    private static final String WATER_HEATER_SPEC_TEXT =
            "Summit AquaMax 50 Gas Water Heater — Specifications.\n"
            + "Tank capacity: 50 gallons. Recovery: 43 GPH at a 90°F rise. Input: 40,000 BTU/hr. "
            + "Factory thermostat setpoint: 120°F. Anode rod: 0.84 in. magnesium, inspect every 2 years. "
            + "T&P relief valve: 150 psi / 210°F.";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final IntegrationConnectionRepository connections;
    private final TechDocService techDocService;
    private final PasswordEncoder encoder;

    public TechCopilotDemoSeeder(TenantRepository tenants,
                                 UserRepository users,
                                 IntegrationConnectionRepository connections,
                                 TechDocService techDocService,
                                 PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.connections = connections;
        this.techDocService = techDocService;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("TechCopilotDemoSeeder failed", err));
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
                .displayName("Summit Mechanical")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(TechCopilotAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Summit Mechanical) + Anthropic connection + manual corpus",
                TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(anthropicConnection(tenantId)))
                        .then(seedCorpus().contextWrite(TenantContextHolder.write(ctx)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — POST /api/v1/techcopilot/ask with "
                        + "\"furnace fault E3 reset steps\" to see the grounded, cited answer", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("techcopilot-demo-password"))
                .displayName("Summit Dispatch")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Per-tenant Anthropic key (sandbox fake) — the grounded-answer model call resolves this. */
    private IntegrationConnection anthropicConnection(UUID tenantId) {
        Map<String, String> secrets = new HashMap<>();
        secrets.put("apiKey", "sk-ant-demo-techcopilot-fake");
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(secrets)
                .build();
    }

    /** Two seeded docs (chunked + embedded via the real ingest) so retrieval has a corpus. */
    private Mono<Void> seedCorpus() {
        return techDocService.create(TechDoc.builder()
                        .title(FURNACE_MANUAL_TITLE)
                        .equipmentType(EquipmentType.FURNACE)
                        .source("Summit GX9 OEM manual")
                        .text(FURNACE_MANUAL_TEXT)
                        .build())
                .then(techDocService.create(TechDoc.builder()
                        .title(WATER_HEATER_SPEC_TITLE)
                        .equipmentType(EquipmentType.WATER_HEATER)
                        .source("Summit AquaMax 50 spec sheet")
                        .text(WATER_HEATER_SPEC_TEXT)
                        .build()))
                .then();
    }
}
