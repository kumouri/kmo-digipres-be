package com.kumouri.kmodigipresbe.contract;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
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

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.7 — ContractLifecycleIT: manual create (incl. amendment), status machine,
 * and SIGNED-is-immutable assertions.
 *
 * <p>Covers: manual POST 201; amendment (parentContractId set); POST /{id}/status
 * illegal transitions → 3709; VOID without reason → 3706; SIGNED is terminal.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ContractLifecycleIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("clc-" + tenantId)
                .displayName("Contract Lifecycle Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@clc.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);
    }

    // -------------------------------------------------------------------------
    // Manual contract create (POST /contracts → 201)
    // -------------------------------------------------------------------------

    @Test
    void manualCreate_201_withTitle() {
        web.post().uri("/contracts")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"Master Services Agreement\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.title").isEqualTo("Master Services Agreement")
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.kind").isEqualTo("GENERIC");
    }

    @Test
    void manualCreate_400_3703_blankTitle() {
        web.post().uri("/contracts")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"\"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3703);
    }

    // -------------------------------------------------------------------------
    // Amendment — parentContractId set
    // -------------------------------------------------------------------------

    @Test
    void amendment_parentContractIdCopied() {
        // Create original
        UUID parentId = web.post().uri("/contracts")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"Original Contract\"}")
                .exchange().expectStatus().isCreated()
                .returnResult(Contract.class)
                .getResponseBody().blockFirst().getId();

        // Create amendment referencing parent
        web.post().uri("/contracts")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"Amendment #1\",\"parentContractId\":\"" + parentId + "\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.title").isEqualTo("Amendment #1")
                .jsonPath("$.parentContractId").isEqualTo(parentId.toString());
    }

    // -------------------------------------------------------------------------
    // Status machine — illegal transitions → 3709
    // -------------------------------------------------------------------------

    @Test
    void setStatus_illegalTransition_draftToSigned_3709() {
        String id = createDraftContract("Test Contract");

        web.post().uri("/contracts/" + id + "/status?status=SIGNED")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3709);
    }

    @Test
    void setStatus_illegalTransition_draftToDraft_3709() {
        String id = createDraftContract("Test Contract 2");

        web.post().uri("/contracts/" + id + "/status?status=DRAFT")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3709);
    }

    @Test
    void setStatus_void_requiresReason_3706() {
        String id = createDraftContract("Contract to Void");

        // VOID without voidReason → 3706
        web.post().uri("/contracts/" + id + "/status?status=VOIDED")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3706);
    }

    @Test
    void setStatus_void_withReason_ok() {
        String id = createDraftContract("Contract with Reason");

        web.post().uri("/contracts/" + id + "/status?status=VOIDED&voidReason=Cancelled+by+client")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("VOIDED")
                .jsonPath("$.voidReason").isEqualTo("Cancelled by client");
    }

    // -------------------------------------------------------------------------
    // SIGNED is terminal — no transition out (the F-D3 immutability invariant)
    // The webhook is the ONLY path to SIGNED; here we seed SIGNED directly via
    // mongo.save to test the status-machine rejection.
    // -------------------------------------------------------------------------

    @Test
    void signedContract_noTransitionOut_3709() {
        // Seed a SIGNED contract directly (bypassing the webhook path to test the
        // status machine in isolation — contract save via ReactiveMongoTemplate
        // bypasses tenant-scope and stamping, so we set tenantId manually).
        Contract signed = Contract.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Executed Contract").status(Contract.Status.SIGNED)
                .build();
        mongo.save(signed).block();

        // Try DRAFT → must reject (SIGNED is terminal)
        web.post().uri("/contracts/" + signed.getId() + "/status?status=DRAFT")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3709);

        // Try VOIDED → must reject (SIGNED is terminal)
        web.post().uri("/contracts/" + signed.getId() + "/status?status=VOIDED&voidReason=Attempt")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3709);

        // Confirm still SIGNED
        Contract still = mongo.findById(signed.getId(), Contract.class).block();
        assertThat(still.getStatus()).isEqualTo(Contract.Status.SIGNED);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private String createDraftContract(String title) {
        return web.post().uri("/contracts")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"" + title + "\"}")
                .exchange().expectStatus().isCreated()
                .returnResult(Contract.class)
                .getResponseBody().blockFirst().getId().toString();
    }
}
