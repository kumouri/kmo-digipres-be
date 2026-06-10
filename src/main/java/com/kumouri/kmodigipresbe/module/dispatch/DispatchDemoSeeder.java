package com.kumouri.kmodigipresbe.module.dispatch;

import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.LatLng;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
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

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T14 (Home "DispatchIQ") — the demo seed for the 60-second "watch this". A {@code @Profile}-gated,
 * idempotent {@code CommandLineRunner} (the {@code TechCopilotDemoSeeder} precedent) that stands up a
 * fictional HVAC tenant <strong>"Comfort Crew HVAC"</strong> with the {@code dispatch} (+
 * {@code home-services} + {@code field-service}) modules on, 3 technicians with varied skills, and 4 open
 * work orders (varied skill / urgency / location) for <em>today</em> — so "request an optimized dispatch →
 * the right tech assigned to each job with an urgent-first, skill-matched rationale → apply → the board
 * reflects it → analytics shows the fit" works on fictional data, deterministically, with no AI / no SMS.
 *
 * <h2>Opt-in only — never runs by default</h2>
 * Gated {@code @Profile("demo-home-dispatch")}; absent from dev / CI / prod default runs. Idempotent on the
 * tenant slug {@code comfort-crew-hvac} — re-running does nothing.
 *
 * <h2>The 60-second "watch this" (the FE leg drives the screen)</h2>
 * <ol>
 *   <li>(boot {@code SPRING_PROFILES_ACTIVE=demo-home-dispatch,dev}) "Four jobs are on the board for today,
 *       none assigned. One is a no-heat EMERGENCY."</li>
 *   <li>{@code GET /api/v1/dispatch/optimize?date=<today>} → the EMERGENCY furnace heads the list, assigned
 *       to a free HVAC tech with a skill-matched, urgent-first rationale; the electrical-panel job is
 *       assigned to Dana (the only ELECTRICAL-skilled tech); the AC + tune-up load-balance across the HVAC
 *       techs; Priya (plumbing-only) is not given an HVAC job.</li>
 *   <li>{@code POST /api/v1/dispatch/apply} with those decisions → the work orders' {@code technicianUserId}
 *       is set; {@code GET /api/v1/home-services/dispatch?date=<today>} (the EXISTING board) now shows the
 *       assignments. {@code GET /api/v1/dispatch/analytics?date=<today>} shows assigned-vs-unassigned + the
 *       skill-match rate + the average fit.</li>
 * </ol>
 */
@Slf4j
@Component
@Profile("demo-home-dispatch")
public class DispatchDemoSeeder implements CommandLineRunner {

    public static final String TENANT_SLUG = "comfort-crew-hvac";
    private static final String ADMIN_EMAIL = "dispatch@comfort-crew-hvac.example";

    // St. Louis-area coordinates so proximity is realistic on the board's haversine.
    private static final LatLng DOWNTOWN = LatLng.of(38.6270, -90.1994);
    private static final LatLng FOREST_PARK = LatLng.of(38.6357, -90.2843);
    private static final LatLng MAPLEWOOD = LatLng.of(38.6126, -90.3257);
    private static final LatLng KIRKWOOD = LatLng.of(38.5834, -90.4068);

    private final TenantRepository tenants;
    private final UserRepository users;
    private final JobSiteRepository jobSites;
    private final WorkOrderRepository workOrders;
    private final PasswordEncoder encoder;

    public DispatchDemoSeeder(TenantRepository tenants,
                              UserRepository users,
                              JobSiteRepository jobSites,
                              WorkOrderRepository workOrders,
                              PasswordEncoder encoder) {
        this.tenants = tenants;
        this.users = users;
        this.jobSites = jobSites;
        this.workOrders = workOrders;
        this.encoder = encoder;
    }

    @Override
    public void run(String... args) {
        seed().subscribe(
                ignored -> {},
                err -> log.error("DispatchDemoSeeder failed", err));
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
                .displayName("Comfort Crew HVAC")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(DispatchAutoConfiguration.MODULE_KEY, "home-services", "field-service"))
                .build();
        log.info("Seeding demo tenant {} (Comfort Crew HVAC) + 3 techs + 4 open work orders", TENANT_SLUG);

        // Techs: Dana (HVAC + ELECTRICAL), Marco (HVAC), Priya (PLUMBING — wrong-skill for HVAC jobs).
        User dana = tech(tenantId, "dana@comfort-crew-hvac.example", "Dana Rivera", List.of("HVAC", "ELECTRICAL"));
        User marco = tech(tenantId, "marco@comfort-crew-hvac.example", "Marco Bauer", List.of("HVAC"));
        User priya = tech(tenantId, "priya@comfort-crew-hvac.example", "Priya Shah", List.of("PLUMBING"));

        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        return tenants.save(tenant)
                .flatMap(saved -> users.save(adminUser(tenantId))
                        .then(users.save(dana))
                        .then(users.save(marco))
                        .then(users.save(priya))
                        .then(seedWorkOrders(today).contextWrite(TenantContextHolder.write(ctx)))
                        .thenReturn(saved))
                .contextWrite(TenantContextHolder.write(ctx))
                .doOnSuccess(t -> log.info("Demo tenant {} seeded — GET /api/v1/dispatch/optimize?date={} "
                        + "to see the optimized board", TENANT_SLUG, today));
    }

    private User adminUser(UUID tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(ADMIN_EMAIL.toLowerCase())
                .passwordHash(encoder.encode("dispatchiq-demo-password"))
                .displayName("Comfort Crew Dispatch")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE)
                // An office dispatcher, not a field tech — a declared non-field skill keeps the
                // admin out of the optimizer's field-tech candidate pool (so the demo board shows
                // exactly the three field techs below).
                .skills(List.of("DISPATCH"))
                .build();
    }

    private User tech(UUID tenantId, String email, String name, List<String> skills) {
        return User.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .email(email.toLowerCase())
                .passwordHash(encoder.encode("dispatchiq-demo-password"))
                .displayName(name)
                .roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE)
                .skills(skills)
                .build();
    }

    /** Four UNASSIGNED open work orders for today — varied skill, urgency, location. */
    private Mono<Void> seedWorkOrders(LocalDate today) {
        return saveSite("No-heat — Downtown", DOWNTOWN)
                .flatMap(s -> saveWo("Furnace no-heat (EMERGENCY)", "HVAC", "EMERGENCY", "LARGE", s.getId(), today, 8))
                .then(saveSite("AC down — Forest Park", FOREST_PARK)
                        .flatMap(s -> saveWo("AC not cooling", "HVAC", "URGENT", "MEDIUM", s.getId(), today, 10)))
                .then(saveSite("Tune-up — Maplewood", MAPLEWOOD)
                        .flatMap(s -> saveWo("Seasonal tune-up", "HVAC", "ROUTINE", "SMALL", s.getId(), today, 13)))
                .then(saveSite("Panel — Kirkwood", KIRKWOOD)
                        .flatMap(s -> saveWo("Electrical panel upgrade", "ELECTRICAL", "URGENT", "LARGE", s.getId(), today, 11)))
                .then();
    }

    private Mono<JobSite> saveSite(String label, LatLng location) {
        return jobSites.save(JobSite.builder()
                .id(UUID.randomUUID())
                .label(label)
                .location(location)
                .build());
    }

    private Mono<WorkOrder> saveWo(String title, String serviceType, String urgency, String valueBand,
                                   UUID jobSiteId, LocalDate day, int hour) {
        Map<String, Object> cf = new HashMap<>();
        cf.put("urgency", urgency);
        cf.put("jobValueBand", valueBand);
        return workOrders.save(WorkOrder.builder()
                .id(UUID.randomUUID())
                .title(title)
                .serviceType(serviceType)
                .status(WorkOrderStatus.SCHEDULED)   // open + unassigned (no technicianUserId)
                .jobSiteId(jobSiteId)
                .scheduledStart(day.atTime(hour, 0).toInstant(ZoneOffset.UTC))
                .customFields(cf)
                .build());
    }
}
