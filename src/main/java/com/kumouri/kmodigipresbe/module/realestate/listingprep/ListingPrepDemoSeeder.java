package com.kumouri.kmodigipresbe.module.realestate.listingprep;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.marketing.ListingMarketingService;
import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T10 (Real Estate "Listing Prep Studio") — the demo seed for the 60-second "watch this". A
 * {@code @Profile}-gated, idempotent {@code CommandLineRunner} (the {@code MidnightResponderDemoSeeder}
 * precedent) that stands up a fictional brokerage tenant <strong>"Harbor Point Realty"</strong> with a
 * listing + two listing photos, so one {@code generate} call produces a full prep pack on fictional data.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-realestate-listingprep")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code harbor-point-realty} — re-running does nothing.
 *
 * <h2>What it seeds</h2>
 * <ul>
 *   <li>Tenant "Harbor Point Realty" + an ADMIN user; module {@code realestate} enabled; a non-zero
 *       {@code aiBudgetUsd} (so the vision + description/email + calendar AI can run).</li>
 *   <li>An {@code IntegrationConnection(anthropic)} carrying the per-tenant {@code apiKey} (a sandbox fake
 *       here — a real key is wired at go-live; the house key is the fallback).</li>
 *   <li>A {@link Listing} ({@code 128 Lighthouse Way}) + two listing photos stored via the shipped RE-4
 *       {@link ListingMarketingService#addPhoto} (the same {@code ListingPhoto}s the prep pack reads).</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-realestate-listingprep,dev}) "Harbor Point just listed a
 *       home and uploaded the photos. Watch the system build the whole launch — safely."</li>
 *   <li>{@code POST /api/v1/realestate/listings/{id}/prep/generate} → "One click turns the photos into a prep
 *       pack: an MLS description, a 4-week social calendar with dated posts, and an email campaign — every
 *       line screened for Fair Housing first."</li>
 *   <li>{@code GET /api/v1/realestate/prep/packs/{id}} → show the dated calendar (weeks 1-4), the description,
 *       the email. "Here's a post the AI tried to write with 'perfect for families' — caught and replaced
 *       with a compliant version before it ever reached the calendar."</li>
 *   <li>{@code POST /api/v1/realestate/prep/packs/{id}/approve} → "The agent approves; it's copy-ready to
 *       paste out. A richer listing also gives the Midnight Responder more to ground buyer answers on."</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-realestate-listingprep")
public class ListingPrepDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "harbor-point-realty";
    private static final String AGENT_EMAIL = "agent@harbor-point-realty.example";
    // A tiny valid 1x1 JPEG (base64) so the stored photo bytes are a real image for the demo vision call.
    private static final byte[] DEMO_PHOTO = Base64.getDecoder().decode(
            "/9j/4AAQSkZJRgABAQEAYABgAAD/2wBDAAgGBgcGBQgHBwcJCQgKDBQNDAsLDBkSEw8UHRofHh0a"
            + "HBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/wAALCAABAAEBAREA/8QAFAABAAAAAAAA"
            + "AAAAAAAAAAAACP/EABQQAQAAAAAAAAAAAAAAAAAAAAD/2gAIAQEAAD8AfwD/2Q==");

    private final TenantRepository tenants;
    private final UserRepository users;
    private final IntegrationConnectionRepository connections;
    private final ListingService listingService;
    private final ListingMarketingService marketingService;
    private final PasswordEncoder encoder;

    public ListingPrepDemoSeeder(TenantRepository tenants,
                                 UserRepository users,
                                 IntegrationConnectionRepository connections,
                                 ListingService listingService,
                                 ListingMarketingService marketingService,
                                 PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.connections = connections;
        this.listingService = listingService;
        this.marketingService = marketingService;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("ListingPrepDemoSeeder failed", err));
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
                .displayName("Harbor Point Realty")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(RealEstateAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();
        log.info("Seeding demo tenant {} (Harbor Point Realty) + a listing + 2 photos for the prep studio",
                TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(anthropicConnection(tenantId)))
                        .then(seedListingWithPhotos(tenantId).contextWrite(TenantContextHolder.write(ctx)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — POST /api/v1/realestate/listings/<id>/"
                        + "prep/generate to build the prep pack end-to-end", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(AGENT_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("harbor-demo-password"))
                .displayName("Harbor Point Agent")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Anthropic connection — a sandbox apiKey (a real key/house key is wired at go-live). */
    private IntegrationConnection anthropicConnection(UUID tenantId) {
        return IntegrationConnection.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", "sk-ant-demo-listingprep-fake")))
                .build();
    }

    /** A listing + two listing photos (via the shipped RE-4 addPhoto), the bytes the prep pack reads back. */
    private Mono<Void> seedListingWithPhotos(UUID tenantId) {
        return listingService.create(Listing.builder()
                        .addressLine("128 Lighthouse Way").city("Galveston").state("TX").zip("77550")
                        .price(new BigDecimal("539000")).beds(4).baths(new BigDecimal("3"))
                        .sqft(2450)
                        .build())
                .flatMap(listing -> marketingService.addPhoto(
                                listing.getId(), DEMO_PHOTO, "image/jpeg", "front.jpg")
                        .then(marketingService.addPhoto(
                                listing.getId(), DEMO_PHOTO, "image/jpeg", "kitchen.jpg")))
                .then();
    }
}
