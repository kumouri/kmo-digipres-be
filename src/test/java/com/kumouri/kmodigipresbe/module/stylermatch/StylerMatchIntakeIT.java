package com.kumouri.kmodigipresbe.module.stylermatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.salonspa.model.AvailabilityWindow;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu;
import com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchResponse;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatchStatus;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 — StylerMatchIntakeIT: the match intake + ranked result over HTTP. Drives the public widget +
 * staff endpoints end-to-end: a request for "balayage + curly hair, Saturday" returns a ranked stylist
 * board with the right stylist first and an explained rationale; the additive {@code StaffMember.specialties}
 * round-trips; the public path resolves the tenant from the token only. The T9 {@code StyleConsultIntakeIT}
 * harness shape.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.security.widget-token-secret=stylermatch-intake-it-secret-0123456789"
})
class StylerMatchIntakeIT {

    // 2026-06-13 is a Saturday; all four seeded stylists' windows are 9-18.
    private static final Instant SAT_2PM =
            ZonedDateTime.of(2026, 6, 13, 14, 0, 0, 0, ZoneOffset.UTC).toInstant();
    private static final Instant SAT_4PM =
            ZonedDateTime.of(2026, 6, 13, 16, 0, 0, 0, ZoneOffset.UTC).toInstant();

    @Autowired WebTestClient web;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired PublicWidgetTokenService widgetTokens;
    @Autowired JwtTokenService jwt;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), StylerMatch.class).block();
        mongo.remove(new Query(), StaffMember.class).block();
        mongo.remove(new Query(), ServiceMenu.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("stylermatch-intake-it-" + tenantId)
                .displayName("StylerMatch Intake IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@stylermatch-intake.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        mongo.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));

        mongo.save(ServiceMenu.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).name("menu")
                .services(List.of(
                        ServiceMenuItem.builder().id("svc-balayage").name("Balayage")
                                .price(new BigDecimal("185")).durationMinutes(180).build(),
                        ServiceMenuItem.builder().id("svc-cut").name("Cut & Style")
                                .price(new BigDecimal("65")).durationMinutes(60).build()))
                .build()).contextWrite(TenantContextHolder.write(ctx)).block();

        // Maya: balayage + curly specialty, eligible all, available Saturday.
        mongo.save(StaffMember.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).displayName("Maya")
                .specialties(List.of("balayage", "curly hair"))
                .eligibleServiceIds(List.of())
                .availabilityWindows(List.of(saturday()))
                .active(true).build()).contextWrite(TenantContextHolder.write(ctx)).block();
        // Sam: cut specialty only, NOT color-eligible, available Saturday.
        mongo.save(StaffMember.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).displayName("Sam")
                .specialties(List.of("cut", "keratin"))
                .eligibleServiceIds(List.of("svc-cut"))
                .availabilityWindows(List.of(saturday()))
                .active(true).build()).contextWrite(TenantContextHolder.write(ctx)).block();
    }

    private AvailabilityWindow saturday() {
        return AvailabilityWindow.builder().dayOfWeek(DayOfWeek.SATURDAY)
                .startTime(LocalTime.of(9, 0)).endTime(LocalTime.of(18, 0)).build();
    }

    private String token() {
        return widgetTokens.issue(tenantId, StylerMatchService.WIDGET_TYPE, Duration.ofHours(1));
    }

    @Test
    void publicMatch_ranksTheRightStylistFirst_withRationale() {
        StylerMatchResponse resp = web.post()
                .uri("/public/integrations/stylermatch/" + token() + "/match")
                .bodyValue(Map.of(
                        "serviceMenuItemId", "svc-balayage",
                        "styleCategory", "balayage",
                        "texture", "curly",
                        "slotStart", SAT_2PM.toString(),
                        "slotEnd", SAT_4PM.toString(),
                        "phone", "+13125551234",
                        "name", "Dana Lee"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(StylerMatchResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.matchId()).isNotNull();
        assertThat(resp.rankedMatches()).hasSize(2);
        // Maya (balayage+curly specialty, color-eligible) ranks first; Sam is penalized (not color-certified).
        assertThat(resp.rankedMatches().get(0).getDisplayName()).isEqualTo("Maya");
        assertThat(resp.rankedMatches().get(0).getRationale())
                .contains(com.kumouri.kmodigipresbe.module.stylermatch.service
                        .StylerMatchScoringService.STYLIST_CONFIRM_NOTE);
        assertThat(resp.rankedMatches().get(0).isEligibleForRequestedService()).isTrue();
        var sam = resp.rankedMatches().stream()
                .filter(r -> r.getDisplayName().equals("Sam")).findFirst().orElseThrow();
        assertThat(sam.isEligibleForRequestedService()).isFalse();
        assertThat(resp.status()).isEqualTo(StylerMatchStatus.NEW);

        // The match persisted; specialties round-tripped on the stylist docs.
        StylerMatch persisted = mongo.findById(resp.matchId(), StylerMatch.class).block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.getRankedMatches()).hasSize(2);
        StaffMember maya = mongo.findAll(StaffMember.class).collectList().block().stream()
                .filter(s -> s.getDisplayName().equals("Maya")).findFirst().orElseThrow();
        assertThat(maya.getSpecialties()).contains("balayage", "curly hair");
    }

    @Test
    void staffMatch_createsRankedMatch_201() {
        // The staff desk creates a match over the authenticated chain (JWT) → 201 + ranked board.
        StylerMatchResponse resp = web.post()
                .uri("/stylermatch/matches")
                .header("Authorization", staffToken)
                .bodyValue(Map.of("styleCategory", "balayage", "serviceMenuItemId", "svc-balayage"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(StylerMatchResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.rankedMatches()).hasSize(2);
        assertThat(resp.rankedMatches().get(0).getDisplayName()).isEqualTo("Maya");
        assertThat(mongo.findAll(StylerMatch.class).collectList().block()).hasSize(1);
    }

    @Test
    void emptyRequest_isRejected_4481() {
        web.post()
                .uri("/public/integrations/stylermatch/" + token() + "/match")
                .bodyValue(Map.of("name", "No Signal")) // no service/style/slot/preference
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4481);
        assertThat(mongo.findAll(StylerMatch.class).collectList().block()).isEmpty();
    }

    @Test
    void wrongWidgetType_isRejected_4480() {
        String wrongType = widgetTokens.issue(tenantId, "quote-intake", Duration.ofHours(1));
        web.post()
                .uri("/public/integrations/stylermatch/" + wrongType + "/match")
                .bodyValue(Map.of("styleCategory", "balayage"))
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4480);
        assertThat(mongo.findAll(StylerMatch.class).collectList().block()).isEmpty();
    }

    @Test
    void noActiveStylists_isRejected_4482() {
        mongo.remove(new Query(), StaffMember.class).block();
        web.post()
                .uri("/public/integrations/stylermatch/" + token() + "/match")
                .bodyValue(Map.of("styleCategory", "balayage"))
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4482);
    }
}
