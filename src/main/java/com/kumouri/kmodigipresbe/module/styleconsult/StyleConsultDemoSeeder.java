package com.kumouri.kmodigipresbe.module.styleconsult;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.catalog.Product;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.repository.ProductRepository;
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
 * T9 (Salon "StyleConsult AI") — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the {@code SalonReviewBoostDemoSeeder} /
 * {@code QuoteNowDemoSeeder} precedent) that stands up a fictional salon tenant
 * <strong>"Lumière Hair Studio"</strong> wired for StyleConsult: salon-spa + chairfill enabled, a
 * {@link ServiceMenu} of bookable services, retail {@link Product}s <strong>with cost + price</strong>
 * (so the margin-aware retail ranking is real), and a Twilio {@link IntegrationConnection} carrying a
 * sandbox {@code bookingLink}.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-salon-styleconsult")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code lumiere-hair-studio} — re-running does nothing. All data is
 * <strong>fictional</strong>; the {@code bookingLink} is an example URL (no live Cal.com). A real
 * booking-link / QR intake needs A2P 10DLC at go-live (a separate human action).
 *
 * <h2>The retail margins (so the ranking is demonstrable)</h2>
 * The seeded retail products carry real {@code unitCost}s so the highest-margin product surfaces first:
 * bond-builder $38/$15 (margin $23), purple shampoo $28/$11 (margin $17), heat protectant $24/$9
 * (margin $15), leave-in conditioner $22/$8 (margin $14). With {@code max-products=3} the consult
 * recommends the top three by margin.
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-salon-styleconsult,dev}) A prospect texts / uploads an
 *       inspiration photo of a balayage look (mint a token via {@code POST /api/v1/styleconsult/tokens},
 *       POST it to {@code /api/v1/public/integrations/styleconsult/{token}/consult}).</li>
 *   <li>In seconds: recommended <strong>services</strong> ("Balayage", "Gloss & Tone") + the
 *       <strong>highest-margin retail</strong> ("Bond Builder", "Purple Shampoo", "Heat Protectant"),
 *       each labeled "your stylist will confirm — nothing is charged automatically."</li>
 *   <li>The prospect accepts → a real salon {@code Booking} is created + a <strong>booking-link SMS</strong>
 *       lands.</li>
 *   <li>{@code GET /api/v1/styleconsult/analytics} → the retail-attach funnel (consults → retail rec →
 *       booked → attach rate).</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-salon-styleconsult")
public class StyleConsultDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "lumiere-hair-studio";
    private static final String ADMIN_EMAIL = "owner@lumiere-hair-studio.example";
    private static final String NOTIFY_PHONE = "+13125550800";
    private static final String NOTIFY_EMAIL = "owner@lumiere-hair-studio.example";
    private static final String FROM_NUMBER = "+13125550799";
    private static final String BOOKING_LINK = "https://lumiere-hair-studio.example/book";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ServiceMenuRepository menus;
    private final ProductRepository products;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public StyleConsultDemoSeeder(TenantRepository tenants,
                                  UserRepository users,
                                  ServiceMenuRepository menus,
                                  ProductRepository products,
                                  IntegrationConnectionRepository connections,
                                  PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.menus = menus;
        this.products = products;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("StyleConsultDemoSeeder failed", err));
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
                .displayName("Lumière Hair Studio")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        SalonSpaAutoConfiguration.MODULE_KEY,
                        ChairFillAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();

        log.info("Seeding demo tenant {} (Lumière Hair Studio) + service menu + margin-priced retail "
                + "+ booking link", TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(menus.save(serviceMenu(tenantId)))
                        .thenMany(products.saveAll(retailProducts(tenantId)))
                        .then(Mono.just(saved)))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — mint a token via POST "
                        + "/api/v1/styleconsult/tokens, POST an inspiration photo to "
                        + "/api/v1/public/integrations/styleconsult/{{token}}/consult to see service + "
                        + "margin-aware retail recommendations; GET /api/v1/styleconsult/analytics shows "
                        + "the retail-attach funnel", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("styleconsult-demo-password"))
                .displayName("Lumière Owner")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: notify targets + a sandbox bookingLink (the accept path texts it; no live Cal.com). */
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
                        "fromNumber", FROM_NUMBER)))
                .config(config)
                .build();
    }

    /** A believable salon service menu — the bookable items StyleConsult recommends + books. */
    private ServiceMenu serviceMenu(UUID tenantId) {
        List<ServiceMenuItem> services = List.of(
                ServiceMenuItem.builder()
                        .id("svc-balayage").name("Balayage")
                        .durationMinutes(180).price(new BigDecimal("185"))
                        .build(),
                ServiceMenuItem.builder()
                        .id("svc-gloss").name("Gloss & Tone")
                        .durationMinutes(60).price(new BigDecimal("75"))
                        .build(),
                ServiceMenuItem.builder()
                        .id("svc-highlights").name("Full Highlights")
                        .durationMinutes(150).price(new BigDecimal("160"))
                        .build(),
                ServiceMenuItem.builder()
                        .id("svc-keratin").name("Keratin Smoothing Treatment")
                        .durationMinutes(120).price(new BigDecimal("220"))
                        .build(),
                ServiceMenuItem.builder()
                        .id("svc-cut").name("Cut & Style")
                        .durationMinutes(60).price(new BigDecimal("65"))
                        .build());
        return ServiceMenu.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .name("Lumière Hair Studio — service menu")
                .services(services)
                .build();
    }

    /**
     * Retail products with real {@code unitCost}s so the margin-aware ranking is demonstrable. Order
     * here is intentionally NOT margin order — the ranker re-sorts by {@code unitPrice − unitCost} desc.
     */
    private List<Product> retailProducts(UUID tenantId) {
        return List.of(
                retail(tenantId, "RET-LEAVEIN", "Leave-In Conditioner",
                        new BigDecimal("22"), new BigDecimal("8")),     // margin 14
                retail(tenantId, "RET-BOND", "Bond Builder Treatment",
                        new BigDecimal("38"), new BigDecimal("15")),    // margin 23 (highest)
                retail(tenantId, "RET-HEAT", "Heat Protectant Spray",
                        new BigDecimal("24"), new BigDecimal("9")),     // margin 15
                retail(tenantId, "RET-PURPLE", "Purple Toning Shampoo",
                        new BigDecimal("28"), new BigDecimal("11")));   // margin 17
    }

    private Product retail(UUID tenantId, String sku, String name, BigDecimal price, BigDecimal cost) {
        return Product.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .sku(sku)
                .name(name)
                .unitPrice(price)
                .unitCost(cost)
                .currency("USD")
                .type(Product.ProductType.GOOD)
                .active(true)
                .build();
    }
}
