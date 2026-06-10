package com.kumouri.kmodigipresbe.module.stylermatch;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.model.AvailabilityWindow;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
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
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the T9 {@code StyleConsultDemoSeeder} precedent) that stands up a
 * fictional salon tenant <strong>"Shear Brilliance Studio"</strong> wired for StylerMatch: salon-spa +
 * chairfill enabled, a {@link ServiceMenu} of bookable services, and {@link StaffMember stylists} with
 * <strong>varied free-text {@code specialties} + {@link AvailabilityWindow availability} +
 * {@code eligibleServiceIds}</strong> so the deterministic match is demonstrable, plus a Twilio
 * {@link IntegrationConnection} carrying a sandbox {@code bookingLink}.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-salon-stylermatch")}, so it is absent from dev / CI / prod default runs.
 * Idempotent on the tenant slug {@code shear-brilliance-studio} — re-running does nothing. All data is
 * <strong>fictional</strong>; the {@code bookingLink} is an example URL (no live Cal.com). A real
 * booking-link / QR intake needs A2P 10DLC at go-live (a separate human action).
 *
 * <h2>The stylists (so the ranking is demonstrable)</h2>
 * <ul>
 *   <li><strong>Maya</strong> — specialties {@code [balayage, curly hair, color correction]}, eligible
 *       for everything, available Tue–Sat. Ranks #1 for a "balayage + curly" request.</li>
 *   <li><strong>Jordan</strong> — specialties {@code [blonde, highlights, gloss]}, eligible for
 *       everything, available Wed–Sun. A strong color match, slightly behind Maya for "curly".</li>
 *   <li><strong>Sam</strong> — specialties {@code [cut, keratin, smoothing]}, eligible ONLY for
 *       {@code [svc-cut, svc-keratin]} (NOT color), available Mon–Fri. Heavily penalized + flagged
 *       "not certified" for a balayage request (and the booking step would hard-reject it).</li>
 *   <li><strong>Riley</strong> — no declared specialties, eligible for everything, available Mon–Sat.
 *       Proves the neutral-low "versatile stylist" path (ranked, never excluded).</li>
 * </ul>
 *
 * <h2>The 60-second "watch this" (documented; the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-salon-stylermatch,dev}) Mint a token via
 *       {@code POST /api/v1/stylermatch/tokens}.</li>
 *   <li>A new client submits {@code POST /api/v1/public/integrations/stylermatch/{token}/match} with
 *       {@code {"serviceMenuItemId":"svc-balayage","styleCategory":"balayage","texture":"curly",
 *       "slotStart":"<a Saturday afternoon ISO>"}}.</li>
 *   <li>In milliseconds: a ranked board — <strong>Maya #1</strong> ("specializes in balayage", available
 *       Saturday), Jordan #2, then Sam (flagged "not certified for Balayage") and Riley ("versatile") —
 *       each carrying "the salon will confirm your stylist; nothing is booked automatically."</li>
 *   <li>The client accepts the top match &rarr; a real CONFIRMED salon {@code Booking} with Maya + a
 *       <strong>booking-link SMS</strong>.</li>
 *   <li>{@code GET /api/v1/stylermatch/analytics} &rarr; the funnel (matches &rarr; booked &rarr;
 *       rank-1 accept rate).</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-salon-stylermatch")
public class StylerMatchDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "shear-brilliance-studio";
    private static final String ADMIN_EMAIL = "owner@shear-brilliance-studio.example";
    private static final String NOTIFY_PHONE = "+13125550900";
    private static final String FROM_NUMBER = "+13125550899";
    private static final String BOOKING_LINK = "https://shear-brilliance-studio.example/book";

    private final TenantRepository tenants;
    private final UserRepository users;
    private final ServiceMenuRepository menus;
    private final StaffMemberRepository staff;
    private final IntegrationConnectionRepository connections;
    private final PasswordEncoder encoder;

    public StylerMatchDemoSeeder(TenantRepository tenants,
                                 UserRepository users,
                                 ServiceMenuRepository menus,
                                 StaffMemberRepository staff,
                                 IntegrationConnectionRepository connections,
                                 PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.menus = menus;
        this.staff = staff;
        this.connections = connections;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("StylerMatchDemoSeeder failed", err));
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
                .displayName("Shear Brilliance Studio")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        SalonSpaAutoConfiguration.MODULE_KEY,
                        ChairFillAutoConfiguration.MODULE_KEY))
                .aiBudgetUsd(new BigDecimal("25.00"))
                .build();

        log.info("Seeding demo tenant {} (Shear Brilliance Studio) + service menu + stylists with "
                + "varied specialties/availability + booking link", TENANT_SLUG);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(connections.save(twilioConnection(tenantId)))
                        .then(menus.save(serviceMenu(tenantId)))
                        .thenMany(staff.saveAll(stylists(tenantId)))
                        .then(Mono.just(saved)))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — mint a token via POST "
                        + "/api/v1/stylermatch/tokens, POST a request to "
                        + "/api/v1/public/integrations/stylermatch/{{token}}/match to see the ranked "
                        + "stylist board; accept the top match to book; GET /api/v1/stylermatch/analytics "
                        + "shows the funnel", TENANT_SLUG));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("stylermatch-demo-password"))
                .displayName("Shear Brilliance Owner")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                .build();
    }

    /** Twilio connection: a sandbox bookingLink (the accept path texts it; no live Cal.com). */
    private IntegrationConnection twilioConnection(UUID tenantId) {
        Map<String, String> config = new HashMap<>();
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

    /** A believable salon service menu — the bookable items StylerMatch matches a stylist for + books. */
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
                .name("Shear Brilliance Studio — service menu")
                .services(services)
                .build();
    }

    /** Stylists with varied specialties + availability + eligibility so the ranking is demonstrable. */
    private List<StaffMember> stylists(UUID tenantId) {
        return List.of(
                StaffMember.builder()
                        .id(UUID.randomUUID()).tenantId(tenantId).displayName("Maya")
                        .specialties(List.of("balayage", "curly hair", "color correction"))
                        .eligibleServiceIds(List.of()) // eligible for everything
                        .availabilityWindows(weekdays(DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY,
                                DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY))
                        .active(true).build(),
                StaffMember.builder()
                        .id(UUID.randomUUID()).tenantId(tenantId).displayName("Jordan")
                        .specialties(List.of("blonde", "highlights", "gloss"))
                        .eligibleServiceIds(List.of())
                        .availabilityWindows(weekdays(DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                                DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY))
                        .active(true).build(),
                StaffMember.builder()
                        .id(UUID.randomUUID()).tenantId(tenantId).displayName("Sam")
                        .specialties(List.of("cut", "keratin", "smoothing"))
                        .eligibleServiceIds(List.of("svc-cut", "svc-keratin")) // NOT color-certified
                        .availabilityWindows(weekdays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY))
                        .active(true).build(),
                StaffMember.builder()
                        .id(UUID.randomUUID()).tenantId(tenantId).displayName("Riley")
                        .specialties(List.of()) // no declared specialty — versatile, ranked-not-excluded
                        .eligibleServiceIds(List.of())
                        .availabilityWindows(weekdays(DayOfWeek.MONDAY, DayOfWeek.TUESDAY,
                                DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY,
                                DayOfWeek.SATURDAY))
                        .active(true).build());
    }

    /** A 9am–6pm availability window on each of the given days. */
    private List<AvailabilityWindow> weekdays(DayOfWeek... days) {
        java.util.List<AvailabilityWindow> windows = new java.util.ArrayList<>();
        for (DayOfWeek d : days) {
            windows.add(AvailabilityWindow.builder()
                    .dayOfWeek(d)
                    .startTime(LocalTime.of(9, 0))
                    .endTime(LocalTime.of(18, 0))
                    .build());
        }
        return windows;
    }
}
