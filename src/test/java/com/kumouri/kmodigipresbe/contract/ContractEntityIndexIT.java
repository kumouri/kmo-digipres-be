package com.kumouri.kmodigipresbe.contract;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.contract.DocumensoWebhookEvent;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.IndexInfo;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * F.3 index gate (Phase F — F.3): proves the three contract collections
 * auto-create, the {@code Contract} partial-unique {@code tenant_number_idx}
 * semantics ({@code ContractNumberIndexInitializer}), the
 * {@code documenso_webhook_events} unique {@code tenant_event_idx}, and that
 * {@link DocumensoWebhookEvent} does NOT produce an {@link AuditEvent} on save
 * (system-ledger, NOT {@code Auditable}).
 *
 * <p>Mirrors {@code InvoiceNumberGeneratorIT} for the partial-index shape and
 * {@code StripeWebhookIdempotencyIT} for the ledger-not-auditable assertion.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ContractEntityIndexIT {

    @Autowired ReactiveMongoTemplate mongo;
    @Autowired TenantRepository tenants;

    private UUID tenantId;

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), DocumensoWebhookEvent.class).block();
        mongo.remove(new Query(), AuditEvent.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId)
                .slug("ctr-idx-" + tenantId)
                .displayName("Contract Index Test Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
    }

    // -------------------------------------------------------------------------
    // Collection existence
    // -------------------------------------------------------------------------

    @Test
    void allThreeCollectionsExist() {
        // Touch each collection so Mongo materialises it, then verify.
        Contract c = Contract.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Probe contract").build();
        mongo.save(c).block();

        ContractTemplate tmpl = ContractTemplate.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Probe template").bodyTemplate("{{body}}").build();
        mongo.save(tmpl).block();

        DocumensoWebhookEvent evt = DocumensoWebhookEvent.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .documensoEventId("evt-probe").eventType("OTHER")
                .receivedAt(Instant.now()).build();
        mongo.save(evt).block();

        Set<String> collections = mongo.getCollectionNames().collectList().block()
                .stream().collect(java.util.stream.Collectors.toSet());

        assertThat(collections).contains("contracts", "contract_templates",
                "documenso_webhook_events");
    }

    // -------------------------------------------------------------------------
    // Contract partial-unique tenant_number_idx
    // -------------------------------------------------------------------------

    /**
     * The partial-unique index must be present on {@code contracts} with
     * {@code unique=true} and a {@code partialFilterExpression} containing
     * {@code contractNumber.$type=string}
     * (owned by {@code ContractNumberIndexInitializer}).
     */
    @Test
    void contractsCollection_hasTenantNumberIdx_partialUnique() {
        List<IndexInfo> indexes = mongo.indexOps(Contract.class)
                .getIndexInfo()
                .collectList()
                .block();

        IndexInfo idx = indexes.stream()
                .filter(i -> "tenant_number_idx".equals(i.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "tenant_number_idx not found on contracts collection"));

        assertThat(idx.isUnique())
                .as("tenant_number_idx must be unique")
                .isTrue();

        String pfe = idx.getPartialFilterExpression();
        assertThat(pfe)
                .as("tenant_number_idx must have a partialFilterExpression")
                .isNotNull();
        // The partial filter must encode contractNumber.$type=string.
        org.bson.Document parsed = org.bson.Document.parse(pfe);
        assertThat(parsed.get("contractNumber")).isNotNull();
        org.bson.Document inner = (org.bson.Document) parsed.get("contractNumber");
        assertThat(inner.get("$type")).isEqualTo("string");
    }

    /**
     * Multiple null-numbered DRAFT contracts for the same tenant must be
     * allowed (the partial-unique filter excludes null values).
     */
    @Test
    void contractsIndex_permitsManyNullNumberedDrafts() {
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        for (int i = 0; i < 5; i++) {
            Contract draft = Contract.builder()
                    .id(UUID.randomUUID()).tenantId(tenantId)
                    .title("Draft " + i).build();
            // contractNumber stays null — the @Builder.Default is absent, so null by default
            mongo.save(draft).contextWrite(TenantContextHolder.write(ctx)).block();
        }

        List<Contract> saved = mongo.findAll(Contract.class).collectList().block();
        assertThat(saved).hasSize(5);
        assertThat(saved).allMatch(c -> c.getContractNumber() == null);
    }

    /**
     * Two contracts for the same tenant with the same non-null contractNumber
     * must be rejected by the unique index backstop.
     */
    @Test
    void contractsIndex_rejectsDuplicateContractNumber() {
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        Contract first = Contract.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("First").contractNumber("CTR-2026-0001").build();
        mongo.save(first).contextWrite(TenantContextHolder.write(ctx)).block();

        Contract second = Contract.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Second").contractNumber("CTR-2026-0001").build();

        StepVerifier.create(
                        mongo.save(second).contextWrite(TenantContextHolder.write(ctx)))
                .expectError(DuplicateKeyException.class)
                .verify();
    }

    /**
     * Two contracts for DIFFERENT tenants sharing the same contractNumber must
     * be allowed (uniqueness is per-tenant).
     */
    @Test
    void contractsIndex_allowsSameNumberAcrossTenants() {
        UUID tenantB = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantB).slug("ctr-idx-b-" + tenantB)
                .displayName("Tenant B").status(Tenant.TenantStatus.ACTIVE).build()).block();

        TenantContext ctxA = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
        TenantContext ctxB = new TenantContext(tenantB, UUID.randomUUID(), Set.of("STAFF"));

        Contract a = Contract.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("A").contractNumber("CTR-2026-0001").build();
        mongo.save(a).contextWrite(TenantContextHolder.write(ctxA)).block();

        Contract b = Contract.builder()
                .id(UUID.randomUUID()).tenantId(tenantB)
                .title("B").contractNumber("CTR-2026-0001").build();
        // Must succeed — different tenant
        Contract saved = mongo.save(b)
                .contextWrite(TenantContextHolder.write(ctxB))
                .block();
        assertThat(saved).isNotNull();
        assertThat(saved.getContractNumber()).isEqualTo("CTR-2026-0001");
    }

    // -------------------------------------------------------------------------
    // DocumensoWebhookEvent unique tenant_event_idx
    // -------------------------------------------------------------------------

    /**
     * The {@code tenant_event_idx} on {@code documenso_webhook_events} must be
     * present and unique.
     */
    @Test
    void documensoWebhookEvents_hasTenantEventIdx_unique() {
        List<IndexInfo> indexes = mongo.indexOps(DocumensoWebhookEvent.class)
                .getIndexInfo()
                .collectList()
                .block();

        IndexInfo idx = indexes.stream()
                .filter(i -> "tenant_event_idx".equals(i.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "tenant_event_idx not found on documenso_webhook_events"));

        assertThat(idx.isUnique())
                .as("tenant_event_idx must be unique")
                .isTrue();
    }

    /**
     * A duplicate (tenant, documensoEventId) insert must be rejected by the
     * unique index (the concurrent-re-delivery guard).
     */
    @Test
    void documensoWebhookEvents_rejectsDuplicateEventId() {
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        DocumensoWebhookEvent first = DocumensoWebhookEvent.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .documensoEventId("evt-abc123").eventType("DOCUMENT_SIGNED")
                .receivedAt(Instant.now()).build();
        mongo.save(first).contextWrite(TenantContextHolder.write(ctx)).block();

        DocumensoWebhookEvent second = DocumensoWebhookEvent.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .documensoEventId("evt-abc123").eventType("DOCUMENT_SIGNED")
                .receivedAt(Instant.now()).build();

        StepVerifier.create(
                        mongo.save(second).contextWrite(TenantContextHolder.write(ctx)))
                .expectError(DuplicateKeyException.class)
                .verify();
    }

    // -------------------------------------------------------------------------
    // DocumensoWebhookEvent is NOT Auditable — no audit_events row on save
    // -------------------------------------------------------------------------

    /**
     * Saving a {@link DocumensoWebhookEvent} must NOT produce an
     * {@link AuditEvent} row (it is a system ledger, NOT {@code Auditable}).
     * Mirrors the rationale in {@link DocumensoWebhookEvent}'s class Javadoc.
     */
    @Test
    void documensoWebhookEvent_saveDoesNotCreateAuditEvent() {
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        DocumensoWebhookEvent evt = DocumensoWebhookEvent.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .documensoEventId("evt-no-audit").eventType("OTHER")
                .receivedAt(Instant.now()).build();
        mongo.save(evt).contextWrite(TenantContextHolder.write(ctx)).block();

        Long auditCount = mongo.count(new Query(), AuditEvent.class).block();
        assertThat(auditCount)
                .as("DocumensoWebhookEvent save must NOT create an audit_events row")
                .isEqualTo(0L);
    }

    // -------------------------------------------------------------------------
    // ContractTemplate index auto-creates
    // -------------------------------------------------------------------------

    /**
     * The {@code tenant_name_idx} on {@code contract_templates} must be present
     * (non-unique compound on {@code tenantId + name}).
     */
    @Test
    void contractTemplates_hasTenantNameIdx() {
        List<IndexInfo> indexes = mongo.indexOps(ContractTemplate.class)
                .getIndexInfo()
                .collectList()
                .block();

        assertThat(indexes.stream().anyMatch(i -> "tenant_name_idx".equals(i.getName())))
                .as("tenant_name_idx must exist on contract_templates")
                .isTrue();
    }
}
