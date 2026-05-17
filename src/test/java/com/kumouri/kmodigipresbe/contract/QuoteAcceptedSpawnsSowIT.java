package com.kumouri.kmodigipresbe.contract;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.quote.Quote;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.7 — QuoteAcceptedSpawnsSowIT: Quote-ACCEPTED→spawn-SOW idempotency proof (F-D6).
 *
 * <p>Asserts:
 * <ul>
 *   <li>Quote not ACCEPTED → 3704</li>
 *   <li>ACCEPTED + active SOW template → 201 SOW Contract (kind=SOW, dealId/quoteId copied)</li>
 *   <li>spawn-contract twice (same and different Idempotency-Key) → exactly ONE Contract
 *       (explicit-boolean {@code existsByTenantIdAndQuoteIdAndTemplateId} proof)</li>
 *   <li>Inactive template → 3705</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteAcceptedSpawnsSowIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID dealId;
    private String adminToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), Quote.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("spawn-sow-" + tenantId)
                .displayName("Spawn SOW Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@spawn.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        // Seed a deal for the quote header ref
        dealId = UUID.randomUUID();
        mongo.save(Deal.builder()
                .id(dealId).tenantId(tenantId)
                .title("Deal for SOW spawn").stage(PipelineStage.PROPOSAL)
                .build()).block();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UUID seedActiveTemplate() {
        ContractTemplate t = ContractTemplate.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("SOW Template").bodyTemplate("SOW Body {{quoteId}}")
                .kind(ContractTemplate.Kind.SOW).active(true).build();
        return mongo.save(t).block().getId();
    }

    private UUID seedInactiveTemplate() {
        ContractTemplate t = ContractTemplate.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Inactive Template").bodyTemplate("Body")
                .kind(ContractTemplate.Kind.SOW).active(false).build();
        return mongo.save(t).block().getId();
    }

    private UUID seedQuote(Quote.Status status) {
        Quote q = Quote.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .status(status).dealId(dealId).build();
        return mongo.save(q).block().getId();
    }

    // -------------------------------------------------------------------------
    // Quote not ACCEPTED → 3704
    // -------------------------------------------------------------------------

    @Test
    void quoteNotAccepted_draftStatus_3704() {
        UUID templateId = seedActiveTemplate();
        UUID quoteId = seedQuote(Quote.Status.DRAFT);

        web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3704);
    }

    @Test
    void quoteNotAccepted_sentStatus_3704() {
        UUID templateId = seedActiveTemplate();
        UUID quoteId = seedQuote(Quote.Status.SENT);

        web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3704);
    }

    // -------------------------------------------------------------------------
    // ACCEPTED + active template → 201 SOW Contract
    // -------------------------------------------------------------------------

    @Test
    void acceptedQuote_activeTemplate_201_sowContract() {
        UUID templateId = seedActiveTemplate();
        UUID quoteId = seedQuote(Quote.Status.ACCEPTED);

        web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.kind").isEqualTo("SOW")
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.quoteId").isEqualTo(quoteId.toString())
                .jsonPath("$.dealId").isEqualTo(dealId.toString())
                .jsonPath("$.templateId").isEqualTo(templateId.toString());
    }

    // -------------------------------------------------------------------------
    // Idempotency — spawn twice → exactly ONE Contract
    // -------------------------------------------------------------------------

    @Test
    void spawnTwice_sameIdempotencyKey_exactlyOneContract() {
        UUID templateId = seedActiveTemplate();
        UUID quoteId = seedQuote(Quote.Status.ACCEPTED);
        String idemKey = UUID.randomUUID().toString();

        // First call — 201
        String id1 = web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", idemKey)
                .exchange().expectStatus().isCreated()
                .returnResult(Contract.class)
                .getResponseBody().blockFirst().getId().toString();

        // Same key — idempotency middleware returns cached 201
        String id2 = web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", idemKey)
                .exchange().expectStatus().isCreated()
                .returnResult(Contract.class)
                .getResponseBody().blockFirst().getId().toString();

        // Domain idempotency: exactly ONE Contract in DB (explicit-boolean probe)
        List<Contract> all = mongo.findAll(Contract.class).collectList().block();
        assertThat(all).hasSize(1);
        assertThat(id1).isEqualTo(id2);
    }

    @Test
    void spawnTwice_differentIdempotencyKey_explicitBooleanProve_exactlyOneContract() {
        UUID templateId = seedActiveTemplate();
        UUID quoteId = seedQuote(Quote.Status.ACCEPTED);

        // First call — 201, new Contract created
        web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange().expectStatus().isCreated();

        // Second call — different key, but same (quoteId, templateId) domain pair.
        // The explicit-boolean existsByTenantIdAndQuoteIdAndTemplateId probe returns the
        // existing contract (not a second create). Result is still 201 (the controller
        // maps @ResponseStatus(CREATED) for the endpoint).
        web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange().expectStatus().isCreated();

        // THE KEY ASSERTION: exactly ONE Contract in the DB, not two.
        List<Contract> all = mongo.findAll(Contract.class).collectList().block();
        assertThat(all)
                .as("explicit-boolean idempotency must produce exactly ONE Contract for (quoteId, templateId)")
                .hasSize(1);
        assertThat(all.get(0).getKind()).isEqualTo(ContractTemplate.Kind.SOW);
        assertThat(all.get(0).getQuoteId()).isEqualTo(quoteId);
        assertThat(all.get(0).getTemplateId()).isEqualTo(templateId);
    }

    // -------------------------------------------------------------------------
    // Inactive template → 3705
    // -------------------------------------------------------------------------

    @Test
    void inactiveTemplate_3705() {
        UUID inactiveTemplateId = seedInactiveTemplate();
        UUID quoteId = seedQuote(Quote.Status.ACCEPTED);

        web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + inactiveTemplateId)
                .header("Authorization", adminToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3705);
    }
}
