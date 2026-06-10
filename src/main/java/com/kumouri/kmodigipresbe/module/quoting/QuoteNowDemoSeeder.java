package com.kumouri.kmodigipresbe.module.quoting;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.quoting.model.JobKind;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBookLineItem;
import com.kumouri.kmodigipresbe.module.quoting.repository.PriceBookRepository;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — the demo seed for the 60-second "watch this". A {@code @Profile}-
 * gated, idempotent {@code CommandLineRunner} (the {@code CallbackDemoSeeder} /
 * {@code MidnightResponderDemoSeeder} precedent) that stands up a fictional HVAC home-services tenant
 * <strong>"Comfort Air HVAC"</strong> wired for QuoteNow: the {@code quoting} module enabled, a Twilio
 * {@link IntegrationConnection} carrying a sandbox {@code bookingLink} + notify targets, and a seeded
 * <strong>HVAC price book</strong> (condenser / furnace / water-heater repair &amp; replace bands +
 * age/condition modifiers + a diagnostic fee) so the synthesized ranges land in believable bands.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-home-quoting")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code comfort-air-hvac} — re-running does nothing. All data is
 * fictional. A real booking-link SMS / QR intake needs A2P 10DLC at go-live (a separate human action);
 * the price book must be reviewed by a human before going live (the wrong-number-liability fence).
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-home-quoting,dev}) A homeowner opens the QuoteNow
 *       widget (or scans the truck/yard-sign QR) and submits a photo of an old condenser + "my AC is
 *       12 years old and blowing warm."</li>
 *   <li>In seconds: a <strong>price range</strong> ("a new condenser is typically <strong>$4,800–
 *       $7,200</strong> installed") + a <strong>REPLACE</strong> recommendation ("at 12 years, a
 *       repair approaches the cost of a far more efficient new unit — <strong>financing
 *       available</strong>") + the mandatory "this is an estimate; final price after an on-site
 *       inspection" line.</li>
 *   <li>The homeowner accepts → gets a <strong>booking-link SMS</strong>.</li>
 *   <li>{@code GET /api/v1/quoting/quotes} → the office inbox shows the pre-qualified job with the
 *       read attributes, the range, and the recommendation.</li>
 * </ol>
 *
 * <p>Q1 seeds the tenant + price book; Q4 adds a quote-intake token + a sample inbox quote.
 */
@Slf4j
@Component
@Profile("demo-home-quoting")
public class QuoteNowDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "comfort-air-hvac";
    private static final String ADMIN_EMAIL = "office@comfort-air-hvac.example";
    private static final String NOTIFY_PHONE = "+13145550700";
    private static final String NOTIFY_EMAIL = "office@comfort-air-hvac.example";
    private static final String TRACKED_PHONE = "+13145550699";
    private static final String BOOKING_LINK = "https://comfort-air-hvac.example/book";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final PriceBookRepository priceBooks;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public QuoteNowDemoSeeder(TenantRepository tenants,
                              UserRepository users,
                              PriceBookRepository priceBooks,
                              IntegrationConnectionRepository connections,
                              PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.priceBooks = priceBooks;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("QuoteNowDemoSeeder failed", err));
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
                .displayName("Comfort Air HVAC")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(QuotingAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Comfort Air HVAC) + HVAC price book + booking link",
                TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(priceBooks.save(hvacPriceBook(tenantId)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — POST a photo + description to "
                        + "/api/v1/public/integrations/quoting/{{token}}/quote (mint a token via POST "
                        + "/api/v1/quoting/tokens) to see the instant range + repair-vs-replace; "
                        + "GET /api/v1/quoting/quotes shows the office inbox", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("quotenow-demo-password"))
                .displayName("Comfort Air Office")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /**
     * Twilio connection: the per-tenant {@code bookingLink} the accept path texts (no live Cal.com),
     * notify targets, and a sandbox token. {@code fromNumber} is the tracked number.
     */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        Map<String, String> config = new HashMap<>();
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

    /**
     * A believable HVAC price book: condenser / furnace / water-heater repair &amp; replace bands with
     * age + severe-failure modifiers, plus a flat diagnostic-visit fee. Tuned so a 12-yr condenser
     * "blowing warm" lands a REPLACE recommendation around $4,800–$7,200.
     */
    private PriceBook hvacPriceBook(UUID tenantId) {
        List<PriceBookLineItem> items = List.of(
                // Condenser (outdoor AC unit)
                PriceBookLineItem.builder()
                        .equipmentType("condenser")
                        .jobKind(JobKind.REPAIR)
                        .low(new BigDecimal("250"))
                        .high(new BigDecimal("1500"))
                        .typicalLifespanYears(15)
                        .agePerYearPct(new BigDecimal("3.0"))
                        .ageMaxPct(new BigDecimal("60"))
                        .severeFailurePct(new BigDecimal("40"))
                        .severeFailureKeywords(List.of("compressor", "not cooling", "blowing warm", "leak"))
                        .build(),
                PriceBookLineItem.builder()
                        .equipmentType("condenser")
                        .jobKind(JobKind.REPLACE)
                        .low(new BigDecimal("4500"))
                        .high(new BigDecimal("7000"))
                        .typicalLifespanYears(15)
                        .agePerYearPct(new BigDecimal("0.5"))
                        .ageMaxPct(new BigDecimal("10"))
                        .build(),
                // Furnace
                PriceBookLineItem.builder()
                        .equipmentType("furnace")
                        .jobKind(JobKind.REPAIR)
                        .low(new BigDecimal("200"))
                        .high(new BigDecimal("1200"))
                        .typicalLifespanYears(20)
                        .agePerYearPct(new BigDecimal("2.5"))
                        .ageMaxPct(new BigDecimal("50"))
                        .severeFailurePct(new BigDecimal("35"))
                        .severeFailureKeywords(List.of("heat exchanger", "cracked", "not igniting", "no heat"))
                        .build(),
                PriceBookLineItem.builder()
                        .equipmentType("furnace")
                        .jobKind(JobKind.REPLACE)
                        .low(new BigDecimal("3500"))
                        .high(new BigDecimal("6500"))
                        .typicalLifespanYears(20)
                        .agePerYearPct(new BigDecimal("0.5"))
                        .ageMaxPct(new BigDecimal("10"))
                        .build(),
                // Water heater
                PriceBookLineItem.builder()
                        .equipmentType("water heater")
                        .jobKind(JobKind.REPAIR)
                        .low(new BigDecimal("150"))
                        .high(new BigDecimal("700"))
                        .typicalLifespanYears(12)
                        .agePerYearPct(new BigDecimal("4.0"))
                        .ageMaxPct(new BigDecimal("60"))
                        .severeFailurePct(new BigDecimal("50"))
                        .severeFailureKeywords(List.of("leak", "flood", "tank", "rust"))
                        .build(),
                PriceBookLineItem.builder()
                        .equipmentType("water heater")
                        .jobKind(JobKind.REPLACE)
                        .low(new BigDecimal("1400"))
                        .high(new BigDecimal("2800"))
                        .typicalLifespanYears(12)
                        .agePerYearPct(new BigDecimal("0.5"))
                        .ageMaxPct(new BigDecimal("8"))
                        .build());

        return PriceBook.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .name("Comfort Air HVAC — demo price book")
                .currency("USD")
                .lineItems(items)
                .diagnosticVisitLow(new BigDecimal("89"))
                .diagnosticVisitHigh(new BigDecimal("149"))
                .build();
    }
}
