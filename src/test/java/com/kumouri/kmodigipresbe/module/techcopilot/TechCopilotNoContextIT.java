package com.kumouri.kmodigipresbe.module.techcopilot;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.techcopilot.model.EquipmentType;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechDoc;
import com.kumouri.kmodigipresbe.module.techcopilot.model.TechQuery;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechCopilotService;
import com.kumouri.kmodigipresbe.module.techcopilot.service.TechDocService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import jakarta.annotation.Nullable;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tech Copilot (T13) — the <strong>no-hallucination guardrail</strong> (the headline safety property,
 * T13-D4). Two cases:
 * <ol>
 *   <li><strong>(b1) empty corpus / no relevant chunk → HANDOFF, the model is NEVER called.</strong> The
 *       no-chunks short-circuit means there is nothing to ground on, so a procedure can never be invented.
 *       Asserted with {@code wireMock.verify(0, ...)} — the strongest no-hallucination guarantee.</li>
 *   <li><strong>(b2) chunks present but the model replies HANDOFF → handoff, no fabricated answer.</strong>
 *       The model WAS called once; the {@code HANDOFF} token is honored.</li>
 * </ol>
 * Both persist a {@code TechQuery} with {@code handoff=true}, no citations, and the answer is the honest
 * "I don't have that documented" line — never a fabricated procedure.
 *
 * <p>Determinism mirrors the other T13 ITs: a {@code @Primary} in-memory {@link VectorIndex}, the shared
 * mock {@code EmbeddingService} (zero vector), WireMock Anthropic.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, TechCopilotNoContextIT.InMemoryVectorIndexConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.techcopilot.enabled=true",
        "kmosf.techcopilot.answer-model=claude-haiku-4-5",
        "kmosf.techcopilot.retrieval-top-k=8",
        "kmosf.techcopilot.handoff-message=I don't have that documented in the manuals on file. "
                + "Escalate to a senior tech."
})
class TechCopilotNoContextIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-t13-nc-fake";

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) {
            wireMock.stop();
        }
    }

    @DynamicPropertySource
    static void anthropicProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired TechDocService techDocService;
    @Autowired TechCopilotService copilot;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired InMemoryVectorIndex vectorIndex;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), TechDoc.class).block();
        mongo.remove(new Query(), TechQuery.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        vectorIndex.clear();

        tenantId = UUID.randomUUID();
        seedTenant(tenantId);
        seedAnthropic(tenantId);
    }

    private TenantContext ctx() {
        return new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void emptyCorpus_handsOff_withoutEverCallingTheModel() {
        // No docs indexed at all → retrieveForCorpus returns zero chunks → the no-chunks short-circuit.
        stubGroundedAnswer("(this canned answer must NEVER be sent — there is nothing to ground on)");

        TechCopilotService.CopilotAnswer ans = copilot.ask("how do I defrost a walk-in freezer?", null)
                .contextWrite(TenantContextHolder.write(ctx())).block();

        assertThat(ans.handoff()).as("empty corpus → HANDOFF").isTrue();
        assertThat(ans.citations()).isEmpty();
        assertThat(ans.answer().toLowerCase()).contains("don't have that documented");

        // The model was NEVER called — the hard no-hallucination guarantee.
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));

        // The persisted query records the handoff with no answer/citations.
        TechQuery persisted = mongo.findById(ans.queryId(), TechQuery.class).block();
        assertThat(persisted).isNotNull();
        assertThat(persisted.isHandoff()).isTrue();
        assertThat(persisted.getAnswer()).isNull();
        assertThat(persisted.getCitations()).isEmpty();
    }

    @Test
    void chunksPresentButModelRepliesHandoffToken_handsOff_noFabricatedAnswer() {
        // A doc exists (so the model IS called) but it doesn't answer the question → model returns HANDOFF.
        create("Furnace Manual", EquipmentType.FURNACE,
                "Routine service: replace the filter every 90 days; blower set screw torque is 50 in-lb.");
        stubExactAnswer("HANDOFF");

        TechCopilotService.CopilotAnswer ans = copilot.ask("what is the refrigerant charge for this unit?",
                        null)
                .contextWrite(TenantContextHolder.write(ctx())).block();

        assertThat(ans.handoff()).as("model HANDOFF token → handoff").isTrue();
        assertThat(ans.citations()).isEmpty();
        assertThat(ans.answer().toLowerCase()).contains("don't have that documented");

        // The model WAS called this time (chunks were present), but produced no fabricated answer.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));

        TechQuery persisted = mongo.findById(ans.queryId(), TechQuery.class).block();
        assertThat(persisted.isHandoff()).isTrue();
        assertThat(persisted.getAnswer()).isNull();
    }

    @Test
    void blankQuestion_isRejected_4494() {
        try {
            copilot.ask("   ", null).contextWrite(TenantContextHolder.write(ctx())).block();
            assertThat(false).as("expected 4494").isTrue();
        } catch (Exception ex) {
            assertThat(ex.getMessage()).contains("Question is required");
        }
    }

    private TechDoc create(String title, EquipmentType type, String text) {
        return techDocService.create(TechDoc.builder()
                        .title(title).equipmentType(type).text(text).build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();
    }

    private void stubGroundedAnswer(String text) {
        stubExactAnswer(text);
    }

    private void stubExactAnswer(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}")));
    }

    private void seedTenant(UUID tid) {
        mongo.save(Tenant.builder()
                .id(tid).slug("t13nc-" + tid)
                .displayName("Summit Mechanical T13 NoContext IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("techcopilot"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    private void seedAnthropic(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    // ── deterministic in-memory VectorIndex (returns all the tenant's chunks) ─────

    @TestConfiguration(proxyBeanMethods = false)
    static class InMemoryVectorIndexConfig {
        @Bean
        @Primary
        InMemoryVectorIndex inMemoryVectorIndex() {
            return new InMemoryVectorIndex();
        }
    }

    static class InMemoryVectorIndex implements VectorIndex {

        record Key(UUID tenantId, String sourceType, UUID sourceId) {
        }

        record Stored(String sourceType, UUID sourceId, Map<String, Object> metadata) {
        }

        private final Map<Key, Stored> store = new ConcurrentHashMap<>();

        void clear() {
            store.clear();
        }

        @Override
        public Mono<Void> upsert(UUID tenantId, String sourceType, UUID sourceId,
                                 float[] vector, Map<String, Object> metadata) {
            store.put(new Key(tenantId, sourceType, sourceId), new Stored(sourceType, sourceId, metadata));
            return Mono.empty();
        }

        @Override
        public Mono<Void> delete(UUID tenantId, String sourceType, UUID sourceId) {
            store.remove(new Key(tenantId, sourceType, sourceId));
            return Mono.empty();
        }

        @Override
        public Flux<VectorSearchHit> search(UUID tenantId, float[] queryVector, int topK,
                                            @Nullable String keywordFilter) {
            return Flux.fromIterable(store.entrySet().stream()
                    .filter(e -> e.getKey().tenantId().equals(tenantId))
                    .map(e -> new VectorSearchHit(e.getValue().sourceType(), e.getValue().sourceId(),
                            0.9, e.getValue().metadata()))
                    .limit(topK)
                    .toList());
        }
    }
}
