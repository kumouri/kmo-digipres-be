package com.kumouri.kmodigipresbe.module.quoting;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.quoting.controller.dto.QuoteInboxCard;
import com.kumouri.kmodigipresbe.module.quoting.controller.dto.QuoteResponse;
import com.kumouri.kmodigipresbe.module.quoting.model.AttributeSource;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.model.Recommendation;
import com.kumouri.kmodigipresbe.module.quoting.model.RepairVsReplace;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteSynthesisService;
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
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T8 — QuoteInboxIT: the office quote-inbox HTTP surface ({@code QuoteInboxController}) — staff list +
 * detail + the per-tenant module gate. The {@code CallbackQueueAndStatsIT} WebTestClient + JWT pattern.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteInboxIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        for (Class<?> c : List.of(QuoteRequest.class, Tenant.class, User.class)) {
            mongo.remove(new Query(), c).block();
        }
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("quote-inbox-it-" + tenantId)
                .displayName("Quote Inbox IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("quoting"))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@quote-inbox.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    private QuoteRequest saveQuote(String phone, String equipmentType, Recommendation rec,
                                   QuoteStatus status, Instant createdAt) {
        return mongo.save(QuoteRequest.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactPhone(phone)
                .attributes(QuoteAttributes.builder()
                        .equipmentType(equipmentType).source(AttributeSource.VISION).confidence(0.75)
                        .build())
                .range(QuoteRange.builder()
                        .low(new BigDecimal("4500")).high(new BigDecimal("7000")).currency("USD")
                        .basis(equipmentType + " — replace")
                        .estimateDisclaimer(QuoteSynthesisService.ESTIMATE_DISCLAIMER)
                        .build())
                .repairVsReplace(RepairVsReplace.builder()
                        .recommendation(rec).rationale("because").financingAvailable(rec == Recommendation.REPLACE)
                        .build())
                .status(status)
                .createdAt(createdAt)
                .build()).block();
    }

    @Test
    void list_returnsNewestFirst() {
        saveQuote("+1111", "condenser", Recommendation.REPLACE, QuoteStatus.NEW,
                Instant.now().minusSeconds(60));
        saveQuote("+2222", "furnace", Recommendation.REPAIR, QuoteStatus.NEW, Instant.now());

        List<QuoteInboxCard> cards = web.get().uri("/quoting/quotes")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(QuoteInboxCard.class).returnResult().getResponseBody();

        assertThat(cards).hasSize(2);
        // Newest first.
        assertThat(cards.get(0).contactPhone()).isEqualTo("+2222");
        assertThat(cards.get(0).equipmentType()).isEqualTo("furnace");
        assertThat(cards.get(1).contactPhone()).isEqualTo("+1111");
    }

    @Test
    void list_filtersByStatus() {
        saveQuote("+1111", "condenser", Recommendation.REPLACE, QuoteStatus.NEW, Instant.now());
        saveQuote("+2222", "furnace", Recommendation.REPAIR, QuoteStatus.ACCEPTED, Instant.now());

        List<QuoteInboxCard> accepted = web.get()
                .uri(b -> b.path("/quoting/quotes").queryParam("status", "ACCEPTED").build())
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(QuoteInboxCard.class).returnResult().getResponseBody();

        assertThat(accepted).hasSize(1);
        assertThat(accepted.get(0).status()).isEqualTo(QuoteStatus.ACCEPTED);
    }

    @Test
    void detail_returnsTheFullQuote_withDisclaimer() {
        QuoteRequest q = saveQuote("+1111", "condenser", Recommendation.REPLACE, QuoteStatus.NEW,
                Instant.now());

        QuoteResponse resp = web.get().uri("/quoting/quotes/{id}", q.getId())
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(QuoteResponse.class).returnResult().getResponseBody();

        assertThat(resp).isNotNull();
        assertThat(resp.quoteId()).isEqualTo(q.getId());
        assertThat(resp.recommendation()).isEqualTo(Recommendation.REPLACE);
        assertThat(resp.financingAvailable()).isTrue();
        assertThat(resp.estimateDisclaimer()).isNotBlank();
    }

    @Test
    void detail_unknownId_is4435() {
        web.get().uri("/quoting/quotes/{id}", UUID.randomUUID())
                .header("Authorization", staffToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4435);
    }

    @Test
    void list_tenantWithoutQuotingModule_is1132() {
        UUID otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(otherTenantId).slug("quote-inbox-nomod-" + otherTenantId)
                .displayName("No-quoting Tenant").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of())   // quoting NOT enabled for the tenant
                .build()).block();
        User otherStaff = User.builder().id(UUID.randomUUID()).tenantId(otherTenantId)
                .email("staff@quote-inbox-nomod.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(otherStaff).block();
        String otherToken = "Bearer " + jwt.mint(otherStaff);

        web.get().uri("/quoting/quotes")
                .header("Authorization", otherToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }
}
