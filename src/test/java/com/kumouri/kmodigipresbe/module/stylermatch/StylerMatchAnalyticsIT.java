package com.kumouri.kmodigipresbe.module.stylermatch;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchAnalytics;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchResponse;
import com.kumouri.kmodigipresbe.module.stylermatch.model.RankedMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatch;
import com.kumouri.kmodigipresbe.module.stylermatch.model.StylerMatchStatus;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
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

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T12 — StylerMatchAnalyticsIT: the match-funnel analytics HTTP surface ({@code StylerMatchController}) —
 * the staff inbox + the <strong>accept-rate by rank</strong> funnel + the per-tenant module gate. The T9
 * {@code StyleConsultAnalyticsIT} WebTestClient + JWT pattern; seeds {@link StylerMatch} rows directly to
 * exercise the funnel math deterministically.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.chairfill.enabled=true",
        "kmosf.modules.salon-spa.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class StylerMatchAnalyticsIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        for (Class<?> c : List.of(StylerMatch.class, Tenant.class, User.class)) {
            mongo.remove(new Query(), c).block();
        }
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("stylermatch-analytics-it-" + tenantId)
                .displayName("StylerMatch Analytics IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill"))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@stylermatch-analytics.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    private void saveMatch(StylerMatchStatus status, Integer selectedRank, double topScore) {
        RankedMatch top = RankedMatch.builder()
                .staffMemberId(UUID.randomUUID()).displayName("Stylist").score(topScore)
                .confidence(1.0).rationale("note").build();
        mongo.save(StylerMatch.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .styleCategory("balayage")
                .rankedMatches(List.of(top, RankedMatch.builder()
                        .staffMemberId(UUID.randomUUID()).displayName("Other").score(topScore - 0.1)
                        .confidence(1.0).rationale("note").build()))
                .status(status)
                .selectedRank(status == StylerMatchStatus.BOOKED ? selectedRank : null)
                .bookingId(status == StylerMatchStatus.BOOKED ? UUID.randomUUID() : null)
                .build()).block();
    }

    @Test
    void analytics_acceptRateByRank() {
        // 4 matches: 3 booked (2 booked rank-1, 1 booked rank-2), 1 NEW.
        saveMatch(StylerMatchStatus.BOOKED, 1, 0.90);
        saveMatch(StylerMatchStatus.BOOKED, 1, 0.80);
        saveMatch(StylerMatchStatus.BOOKED, 2, 0.70);
        saveMatch(StylerMatchStatus.NEW, null, 0.60);

        StylerMatchAnalytics a = web.get().uri("/stylermatch/analytics")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(StylerMatchAnalytics.class).returnResult().getResponseBody();

        assertThat(a).isNotNull();
        assertThat(a.totalMatches()).isEqualTo(4);
        assertThat(a.matchesBooked()).isEqualTo(3);
        assertThat(a.bookingRate()).isEqualTo(0.75);          // 3 booked / 4 total
        assertThat(a.top1BookedCount()).isEqualTo(2);
        assertThat(a.top2BookedCount()).isEqualTo(1);
        assertThat(a.top3PlusBookedCount()).isZero();
        assertThat(a.top1AcceptRate()).isCloseTo(2.0 / 3.0,
                org.assertj.core.data.Offset.offset(0.001));  // 2 rank-1 booked / 3 booked
        // avg top score across all 4 matches: (0.90 + 0.80 + 0.70 + 0.60) / 4 = 0.75.
        assertThat(a.avgTopScore()).isCloseTo(0.75, org.assertj.core.data.Offset.offset(0.001));
    }

    @Test
    void analytics_noBookings_ratesZero_neverDivideByZero() {
        saveMatch(StylerMatchStatus.NEW, null, 0.9);
        saveMatch(StylerMatchStatus.NEW, null, 0.5);

        StylerMatchAnalytics a = web.get().uri("/stylermatch/analytics")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(StylerMatchAnalytics.class).returnResult().getResponseBody();

        assertThat(a.totalMatches()).isEqualTo(2);
        assertThat(a.matchesBooked()).isZero();
        assertThat(a.bookingRate()).isZero();
        assertThat(a.top1AcceptRate()).isZero();
    }

    @Test
    void inbox_listsMatchesNewestFirst() {
        saveMatch(StylerMatchStatus.NEW, null, 0.9);
        saveMatch(StylerMatchStatus.BOOKED, 1, 0.8);

        List<StylerMatchResponse> rows = web.get().uri("/stylermatch/matches")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(StylerMatchResponse.class).returnResult().getResponseBody();

        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> assertThat(r.rankedMatches()).hasSize(2));
    }

    @Test
    void analytics_tenantWithoutChairfillModule_is1132() {
        UUID otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(otherTenantId).slug("stylermatch-nomod-" + otherTenantId)
                .displayName("No-chairfill Tenant").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa"))   // chairfill NOT enabled for the tenant
                .build()).block();
        User otherStaff = User.builder().id(UUID.randomUUID()).tenantId(otherTenantId)
                .email("staff@stylermatch-nomod.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(otherStaff).block();
        String otherToken = "Bearer " + jwt.mint(otherStaff);

        web.get().uri("/stylermatch/analytics")
                .header("Authorization", otherToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }
}
