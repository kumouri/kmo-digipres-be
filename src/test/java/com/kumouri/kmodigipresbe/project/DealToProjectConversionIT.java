package com.kumouri.kmodigipresbe.project;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
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
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-C1: Deal→Project one-click conversion on WON deal (201, fields copied).
 * AC-C2: Conversion idempotency (second call → 200, same project, exactly 1 document).
 *        Also: non-WON deal → 409 with errorCode 3432.
 *        Also: unknown deal → 404 with errorCode 3431.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class DealToProjectConversionIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.dropCollection("project_code_counters").block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("conv-" + tenantId)
                .displayName("Conv Tenant").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@conv.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    private Deal wonDeal(UUID contactId, UUID companyId, UUID ownerId) {
        return Deal.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .title("Big Deal")
                .stage(PipelineStage.WON)
                .value(new BigDecimal("5000.00"))
                .primaryContactId(contactId)
                .companyId(companyId)
                .ownerId(ownerId)
                .build();
    }

    @Test
    void wonDealCreatesProject201WithFieldsCopied() {
        UUID contactId = UUID.randomUUID();
        UUID companyId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        Deal deal = wonDeal(contactId, companyId, ownerId);
        mongo.save(deal).block();

        web.post().uri("/projects/from-deal/" + deal.getId())
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.dealId").isEqualTo(deal.getId().toString())
                .jsonPath("$.primaryContactId").isEqualTo(contactId.toString())
                .jsonPath("$.companyId").isEqualTo(companyId.toString())
                .jsonPath("$.ownerId").isEqualTo(ownerId.toString())
                .jsonPath("$.status").isEqualTo("PLANNING")
                .jsonPath("$.code").value(code -> assertThat((String) code).matches("PRJ-\\d{4}-\\d{3}"));
    }

    @Test
    void secondCallReturns200WithSameProject() {
        Deal deal = wonDeal(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        mongo.save(deal).block();

        // First call → 201
        Project first = web.post().uri("/projects/from-deal/" + deal.getId())
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Project.class)
                .returnResult().getResponseBody();

        // Second call → 200 (idempotent)
        Project second = web.post().uri("/projects/from-deal/" + deal.getId())
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isOk()
                .expectBody(Project.class)
                .returnResult().getResponseBody();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(second.getId()).isEqualTo(first.getId());

        // Assert exactly one document in the projects collection for this dealId
        long count = mongo.find(new Query(org.springframework.data.mongodb.core.query.Criteria.where("dealId").is(deal.getId())),
                Project.class).collectList().block().size();
        assertThat(count).isEqualTo(1);
    }

    @Test
    void nonWonDealReturns409() {
        Deal qualifiedDeal = Deal.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .title("Not Won Yet")
                .stage(PipelineStage.QUALIFIED)
                .build();
        mongo.save(qualifiedDeal).block();

        web.post().uri("/projects/from-deal/" + qualifiedDeal.getId())
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT)
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3432);
    }

    @Test
    void unknownDealReturns404() {
        web.post().uri("/projects/from-deal/" + UUID.randomUUID())
                .header("Authorization", token)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(3431);
    }
}
