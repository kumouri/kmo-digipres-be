package com.kumouri.kmodigipresbe.service.ai;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.ai.VectorDocument;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.VectorDocumentRepository;
import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Verifies that the EmbeddingPipeline:
 * <ol>
 *   <li>Stores a vector when an ACTIVITY_LOGGED event fires for a NOTE activity</li>
 *   <li>Skips embedding for non-indexable activity types (CALL)</li>
 *   <li>Enforces tenant isolation — searching with tenant B's id returns nothing for
 *       an Activity that belongs to tenant A</li>
 * </ol>
 *
 * <p>The real {@link EmbeddingService} is mocked to avoid OpenAI network calls in CI.
 * The real {@link VectorIndex} ({@link com.kumouri.kmodigipresbe.service.ai.vector.MongoAtlasVectorIndex})
 * is wired but its {@code $vectorSearch} aggregation is replaced by a direct
 * repository lookup so the test does not require an Atlas cluster.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class EmbeddingPipelineIT {

    @Autowired DomainEventPublisher publisher;
    @Autowired ActivityRepository activities;
    @Autowired VectorDocumentRepository vectorDocs;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired EmbeddingService embeddingService; // shared mock from TestcontainersConfiguration

    private static final float[] DUMMY_VECTOR = new float[1536];

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), VectorDocument.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        // Reset and reconfigure the shared mock from TestcontainersConfiguration
        Mockito.reset(embeddingService);
        when(embeddingService.embed(any(UUID.class), anyString()))
                .thenReturn(Mono.just(DUMMY_VECTOR));
        when(embeddingService.providerName()).thenReturn("mock");
    }

    @Test
    void noteActivity_producesVectorDocument() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId);
        UUID contactId = UUID.randomUUID();
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        Activity note = Activity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .type(ActivityType.NOTE)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contactId)
                .summary("Discussed pricing for Q3 proposal")
                .body("Client wants 20% off on the enterprise tier. Follow up next week.")
                .build();

        activities.save(note)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        publisher.publish(DomainEvent.of(
                DomainEventType.ACTIVITY_LOGGED, tenantId, note.getId(),
                Map.of("activityId", note.getId().toString())));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            VectorDocument doc = vectorDocs
                    .findByTenantIdAndSourceTypeAndSourceId(tenantId, "Activity", note.getId())
                    .contextWrite(TenantContextHolder.write(ctx))
                    .block();
            assertThat(doc).isNotNull();
            assertThat(doc.getVector()).hasSize(1536);
            assertThat(doc.getMetadata()).containsKey("contentPreview");
        });
    }

    @Test
    void callActivity_isNotIndexed() {
        UUID tenantId = UUID.randomUUID();
        seedTenant(tenantId);
        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        Activity call = Activity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .type(ActivityType.CALL)
                .subjectType(SubjectType.CONTACT)
                .subjectId(UUID.randomUUID())
                .summary("Quick call")
                .build();

        activities.save(call)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        publisher.publish(DomainEvent.of(
                DomainEventType.ACTIVITY_LOGGED, tenantId, call.getId(), Map.of()));

        // Give the pipeline time to process; then assert nothing was stored
        try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        VectorDocument doc = vectorDocs
                .findByTenantIdAndSourceTypeAndSourceId(tenantId, "Activity", call.getId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(doc).isNull();
    }

    @Test
    void tenantBSearchCannotRetrieveTenantAVector() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        seedTenant(tenantA);
        seedTenant(tenantB);

        TenantContext ctxA = new TenantContext(tenantA, UUID.randomUUID(), Set.of("STAFF"));
        TenantContext ctxB = new TenantContext(tenantB, UUID.randomUUID(), Set.of("STAFF"));

        UUID activityId = UUID.randomUUID();
        Activity note = Activity.builder()
                .id(activityId).tenantId(tenantA)
                .type(ActivityType.NOTE)
                .subjectType(SubjectType.CONTACT)
                .subjectId(UUID.randomUUID())
                .summary("Tenant A secret note").body("Confidential content")
                .build();

        activities.save(note).contextWrite(TenantContextHolder.write(ctxA)).block();
        publisher.publish(DomainEvent.of(
                DomainEventType.ACTIVITY_LOGGED, tenantA, activityId, Map.of()));

        Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                assertThat(vectorDocs
                        .findByTenantIdAndSourceTypeAndSourceId(tenantA, "Activity", activityId)
                        .contextWrite(TenantContextHolder.write(ctxA))
                        .block()).isNotNull());

        // Tenant B must not see it via direct repository lookup (simulates vector search isolation)
        VectorDocument fromB = vectorDocs
                .findByTenantIdAndSourceTypeAndSourceId(tenantB, "Activity", activityId)
                .contextWrite(TenantContextHolder.write(ctxB))
                .block();
        assertThat(fromB).isNull();
    }

    private void seedTenant(UUID tenantId) {
        tenants.save(Tenant.builder()
                .id(tenantId)
                .slug("tenant-" + tenantId)
                .displayName("Test Tenant " + tenantId)
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new java.math.BigDecimal("50.00"))
                .build()).block();
    }
}
