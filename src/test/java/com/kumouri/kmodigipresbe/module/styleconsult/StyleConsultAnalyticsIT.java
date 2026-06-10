package com.kumouri.kmodigipresbe.module.styleconsult;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultAnalytics;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultInboxCard;
import com.kumouri.kmodigipresbe.module.styleconsult.model.RetailRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.ServiceRecommendation;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T9 — StyleConsultAnalyticsIT: the office consult-inbox + the retail-attach analytics HTTP surface
 * ({@code StyleConsultController}) — staff list + the retail-attach funnel + the per-tenant module gate.
 * The {@code QuoteInboxIT} WebTestClient + JWT pattern. Seeds {@link StyleConsult} rows directly to
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
class StyleConsultAnalyticsIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        for (Class<?> c : List.of(StyleConsult.class, Tenant.class, User.class)) {
            mongo.remove(new Query(), c).block();
        }
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("styleconsult-analytics-it-" + tenantId)
                .displayName("StyleConsult Analytics IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa", "chairfill"))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@styleconsult-analytics.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    private StyleConsult saveConsult(StyleConsultStatus status, boolean withRetail, String marginEach) {
        List<ServiceRecommendation> services = List.of(ServiceRecommendation.builder()
                .serviceMenuItemId("svc-balayage").name("Balayage").price(new BigDecimal("185"))
                .rationale("note").build());
        List<RetailRecommendation> retail = withRetail
                ? List.of(RetailRecommendation.builder()
                        .productId(UUID.randomUUID()).sku("RET-BOND").name("Bond Builder")
                        .price(new BigDecimal("38")).cost(new BigDecimal("15"))
                        .marginAmount(new BigDecimal(marginEach)).rationale("note").build())
                : List.of();
        return mongo.save(StyleConsult.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactPhone("+1314" + (1000 + (int) (Math.random() * 8999)))
                .serviceRecommendations(services)
                .retailRecommendations(retail)
                .status(status)
                .bookingId(status == StyleConsultStatus.BOOKED ? UUID.randomUUID() : null)
                .build()).block();
    }

    @Test
    void analytics_retailAttachFunnel() {
        // 4 consults: 3 carry retail, 2 are booked (both booked carry retail) → attach rate 2/2 = 1.0.
        saveConsult(StyleConsultStatus.BOOKED, true, "23");   // booked + retail
        saveConsult(StyleConsultStatus.BOOKED, true, "17");   // booked + retail
        saveConsult(StyleConsultStatus.NEW, true, "14");      // not booked, has retail
        saveConsult(StyleConsultStatus.NEW, false, "0");      // not booked, no retail

        StyleConsultAnalytics a = web.get().uri("/styleconsult/analytics")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(StyleConsultAnalytics.class).returnResult().getResponseBody();

        assertThat(a).isNotNull();
        assertThat(a.totalConsults()).isEqualTo(4);
        assertThat(a.consultsWithRetail()).isEqualTo(3);
        assertThat(a.consultsBooked()).isEqualTo(2);
        assertThat(a.bookedWithRetail()).isEqualTo(2);
        assertThat(a.retailAttachRate()).isEqualTo(1.0);   // 2 booked-with-retail / 2 booked
        assertThat(a.bookingRate()).isEqualTo(0.5);        // 2 booked / 4 total
        // avg margin across all recommended retail items: (23 + 17 + 14) / 3 = 18.00.
        assertThat(a.avgRecommendedRetailMargin()).isEqualByComparingTo("18.00");
    }

    @Test
    void analytics_noBookings_attachRateZero_neverDivideByZero() {
        saveConsult(StyleConsultStatus.NEW, true, "23");
        saveConsult(StyleConsultStatus.NEW, false, "0");

        StyleConsultAnalytics a = web.get().uri("/styleconsult/analytics")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(StyleConsultAnalytics.class).returnResult().getResponseBody();

        assertThat(a.totalConsults()).isEqualTo(2);
        assertThat(a.consultsBooked()).isZero();
        assertThat(a.retailAttachRate()).isZero();
        assertThat(a.bookingRate()).isZero();
    }

    @Test
    void inbox_listsConsultsNewestFirst() {
        saveConsult(StyleConsultStatus.NEW, true, "23");
        saveConsult(StyleConsultStatus.BOOKED, true, "17");

        List<StyleConsultInboxCard> cards = web.get().uri("/styleconsult/consults")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(StyleConsultInboxCard.class).returnResult().getResponseBody();

        assertThat(cards).hasSize(2);
        assertThat(cards).allSatisfy(c -> assertThat(c.retailCount()).isEqualTo(1));
    }

    @Test
    void analytics_tenantWithoutChairfillModule_is1132() {
        UUID otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(otherTenantId).slug("styleconsult-nomod-" + otherTenantId)
                .displayName("No-chairfill Tenant").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("salon-spa"))   // chairfill NOT enabled for the tenant
                .build()).block();
        User otherStaff = User.builder().id(UUID.randomUUID()).tenantId(otherTenantId)
                .email("staff@styleconsult-nomod.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(otherStaff).block();
        String otherToken = "Bearer " + jwt.mint(otherStaff);

        web.get().uri("/styleconsult/analytics")
                .header("Authorization", otherToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }
}
