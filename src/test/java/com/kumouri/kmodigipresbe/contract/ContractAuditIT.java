package com.kumouri.kmodigipresbe.contract;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.contract.DocumensoWebhookEvent;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.awaitility.Awaitility;
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

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.7 — ContractAuditIT: auditing invariants for the contracts vertical.
 *
 * <p>Asserts:
 * <ul>
 *   <li>After {@code POST /contracts} an {@code audit_events} CREATE row exists
 *       for the {@link Contract} (it is {@code Auditable})</li>
 *   <li>Saving a {@link DocumensoWebhookEvent} produces NO audit row
 *       (system-ledger, NOT {@code Auditable} — the validator invariant)</li>
 * </ul>
 *
 * <p>DB asserts via {@code mongo.findAll/findById} (bypass tenant scope — the
 * Phase-C/D/E lesson).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ContractAuditIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;

    @BeforeEach
    void seed() {
        // Clear entities first (some implement Auditable, so their removal won't trigger
        // auditing since AuditingCallback fires on SAVE, not delete). Then clear Users
        // and Tenants, and finally clear AuditEvents LAST so that any audit rows created
        // by the User save (User implements Auditable) are wiped before assertions.
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), DocumensoWebhookEvent.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("audit-" + tenantId)
                .displayName("Audit Test Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@audit.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        // Clear AuditEvents AFTER User save: User implements Auditable, so saving the User
        // above produces an audit row. Clearing here ensures a clean baseline for each test
        // without contamination from the seed User's audit event.
        mongo.remove(new Query(), AuditEvent.class).block();
    }

    // -------------------------------------------------------------------------
    // Contract.create → AuditEvent CREATE row
    // -------------------------------------------------------------------------

    @Test
    void postContract_createsAuditEvent() {
        // POST /contracts → creates a DRAFT Contract
        Contract created = web.post().uri("/contracts")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"title\":\"Audit Test Contract\"}")
                .exchange()
                .expectStatus().isCreated()
                .returnResult(Contract.class)
                .getResponseBody().blockFirst();

        UUID contractId = created.getId();

        // The AuditingCallback fires asynchronously — await the audit row
        Awaitility.await().atMost(5, TimeUnit.SECONDS).untilAsserted(() -> {
            List<AuditEvent> auditEvents = mongo.findAll(AuditEvent.class).collectList().block();
            assertThat(auditEvents)
                    .as("Contract.create must produce at least one audit_events CREATE row")
                    .isNotEmpty();

            boolean contractAuditFound = auditEvents.stream()
                    .anyMatch(e -> contractId.equals(e.getEntityId())
                            && "Contract".equals(e.getEntityType()));
            assertThat(contractAuditFound)
                    .as("audit_events must contain a CREATE row for the Contract entity")
                    .isTrue();
        });
    }

    // -------------------------------------------------------------------------
    // DocumensoWebhookEvent.save → NO AuditEvent (system-ledger, NOT Auditable)
    // -------------------------------------------------------------------------

    @Test
    void documensoWebhookEvent_saveDoesNotCreateAuditEvent() {
        // Save a DocumensoWebhookEvent directly via mongo (bypasses tenant scope)
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        DocumensoWebhookEvent evt = DocumensoWebhookEvent.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .documensoEventId("evt-audit-test-" + UUID.randomUUID())
                .eventType("DOCUMENT_SIGNED")
                .receivedAt(Instant.now())
                .build();
        mongo.save(evt).contextWrite(TenantContextHolder.write(ctx)).block();

        // Brief wait to allow any spurious async audit write to appear
        try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        Long auditCount = mongo.count(new Query(), AuditEvent.class).block();
        assertThat(auditCount)
                .as("DocumensoWebhookEvent save must NOT produce an audit_events row "
                        + "(system-ledger, NOT Auditable — the validator invariant)")
                .isEqualTo(0L);
    }
}
