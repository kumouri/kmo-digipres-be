package com.kumouri.kmodigipresbe.module.proposals;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOW-2 — ProposalDraftIT: drives {@code POST /proposals/draft} end-to-end (controller → service →
 * DRAFT Quote + SowDraft) against WireMock Anthropic (the {@code DunningDispatchIT} /
 * {@code MoleVisionServiceIT} WireMock-Anthropic pattern), plus the {@code GET /proposals/{id}} read,
 * the validation / module-off error paths, and the {@code PROPOSAL_DRAFTED} advisory event.
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic goes to WireMock via {@code kmosf.ai.anthropic.base-url}
 * ({@code @DynamicPropertySource}) — never a real host; the {@code apiKey} is a sandbox fake. The
 * proposals module is default-OFF ({@code matchIfMissing=false}); this IT opts the deployment in via
 * {@code kmosf.modules.proposals.enabled=true} and the per-tenant membership via
 * {@code Tenant.enabledModules}.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li><strong>happy path</strong> — canned SOW JSON → 201, a DRAFT {@link Quote} persisted with the
 *       expected line items + computed total + the prose stored on the {@link SowDraft} +
 *       {@code aiApplied=true}; an advisory {@code PROPOSAL_DRAFTED} fires; the drafted SOW is then
 *       read back via {@code GET /proposals/{id}};</li>
 *   <li><strong>garbage AI</strong> — a non-JSON answer → 201, a draft with {@code aiApplied=false}
 *       and no line items (graceful — never throws);</li>
 *   <li><strong>budget exhausted</strong> — a zero-budget tenant → 201, a graceful empty
 *       {@code aiApplied=false} draft with ZERO WireMock spend (the budget gate trips before the
 *       call; the AI failure never blocks the draft);</li>
 *   <li><strong>blank notes</strong> → {@code 4621}/400 (before any AI spend);</li>
 *   <li><strong>module off</strong> — a tenant without the {@code proposals} module → {@code 4620}/404
 *       (the per-tenant membership gate, the established off-posture).</li>
 * </ol>
 *
 * <p>Shard-safe: self-clean {@code mongo.remove} {@code @BeforeEach}; no {@code application-test.properties}
 * / {@code build.gradle} shard change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.proposals.enabled=true",
        // Force the cheaper Haiku cost-estimate branch (any model works for the WireMock stub).
        "kmosf.modules.proposals.draft-model=claude-haiku-4-5"
})
class ProposalDraftIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-sow-fake";

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void anthropicProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private String staffToken;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Quote.class).block();
        mongo.remove(new Query(), SowDraft.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, new BigDecimal("5.00"), Set.of("proposals"));
        seedAnthropic(tenantId);
        staffToken = "Bearer " + jwt.mint(seedStaff(tenantId));

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    // ── happy path ──────────────────────────────────────────────────────────

    @Test
    void draft_cannedSow_persistsPricedDraftQuoteAndProse_emitsEvent_andReadsBack() {
        // Two priced lines: 2 × 4000 = 8000 and 1 × 1500 = 1500 → subtotal/total 9500.00.
        stubReply("{\"lineItems\":["
                + "{\"description\":\"Custom CRM build\",\"quantity\":2,\"unitPrice\":4000},"
                + "{\"description\":\"Discovery & design\",\"quantity\":1,\"unitPrice\":1500}],"
                + "\"scope\":\"Build a custom CRM for the client.\","
                + "\"deliverables\":\"A deployed CRM with onboarding.\","
                + "\"assumptions\":\"Client provides timely feedback.\","
                + "\"timeline\":\"Roughly eight weeks across two phases.\"}");

        // AI-10: the contactId must be a real contact OF THIS TENANT (cross-tenant/unknown refs are now
        // rejected before the Quote is created), so seed one and reference it.
        UUID contactId = seedContact(tenantId);
        web.post().uri("/proposals/draft")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"notes\":\"Client wants a custom CRM to replace spreadsheets.\","
                        + "\"contactId\":\"" + contactId + "\",\"currency\":\"USD\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.quote.id").isNotEmpty()
                .jsonPath("$.quote.status").isEqualTo("DRAFT")
                .jsonPath("$.quote.total").isEqualTo(9500.00)
                .jsonPath("$.quote.lineItems.length()").isEqualTo(2)
                .jsonPath("$.quote.contactId").isEqualTo(contactId.toString())
                .jsonPath("$.sowDraft.aiApplied").isEqualTo(true)
                .jsonPath("$.sowDraft.scope").isEqualTo("Build a custom CRM for the client.")
                .jsonPath("$.sowDraft.timeline").isEqualTo("Roughly eight weeks across two phases.");

        // Persisted: exactly one DRAFT Quote priced at 9500.00, and its SowDraft prose.
        Quote quote = mongo.findOne(new Query(Criteria.where("tenantId").is(tenantId)), Quote.class).block();
        assertThat(quote).isNotNull();
        assertThat(quote.getStatus()).isEqualTo(Quote.Status.DRAFT);
        assertThat(quote.getTotal()).isEqualByComparingTo("9500.00");
        assertThat(quote.getLineItems()).hasSize(2);

        SowDraft sow = mongo.findOne(
                new Query(Criteria.where("tenantId").is(tenantId).and("quoteId").is(quote.getId())),
                SowDraft.class).block();
        assertThat(sow).isNotNull();
        assertThat(sow.isAiApplied()).isTrue();
        assertThat(sow.getScope()).isEqualTo("Build a custom CRM for the client.");
        assertThat(sow.getDeliverables()).isEqualTo("A deployed CRM with onboarding.");

        // Exactly one Anthropic call (proves §7 base-url + the spend path).
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));

        // The advisory PROPOSAL_DRAFTED fired for this Quote with the right payload.
        DomainEvent drafted = observed.stream()
                .filter(e -> DomainEventType.PROPOSAL_DRAFTED.equals(e.type()))
                .findFirst().orElseThrow();
        assertThat(drafted.tenantId()).isEqualTo(tenantId);
        assertThat(drafted.subjectId()).isEqualTo(quote.getId());
        assertThat(drafted.payload()).containsEntry("lineItemCount", 2);
        assertThat(drafted.payload()).containsEntry("aiApplied", true);
        assertThat(drafted.payload()).containsEntry("contactId", contactId.toString());

        // GET /proposals/{id} reads the drafted SOW back (Quote + prose).
        web.get().uri("/proposals/" + quote.getId())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.quote.id").isEqualTo(quote.getId().toString())
                .jsonPath("$.quote.total").isEqualTo(9500.00)
                .jsonPath("$.sowDraft.scope").isEqualTo("Build a custom CRM for the client.");
    }

    // ── garbage AI → graceful aiApplied=false draft, no throw ─────────────────

    @Test
    void draft_garbageAiResponse_materializesEmptyAiNotAppliedDraft_noThrow() {
        stubReply("Sure! Here is your SOW: it should cost about five thousand dollars, maybe more.");

        web.post().uri("/proposals/draft")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"notes\":\"Small marketing site refresh.\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.quote.status").isEqualTo("DRAFT")
                .jsonPath("$.quote.lineItems.length()").isEqualTo(0)
                .jsonPath("$.sowDraft.aiApplied").isEqualTo(false);

        Quote quote = mongo.findOne(new Query(Criteria.where("tenantId").is(tenantId)), Quote.class).block();
        assertThat(quote).isNotNull();
        assertThat(quote.getStatus()).isEqualTo(Quote.Status.DRAFT);
        assertThat(quote.getLineItems()).isEmpty();

        SowDraft sow = mongo.findOne(new Query(Criteria.where("tenantId").is(tenantId)), SowDraft.class).block();
        assertThat(sow).isNotNull();
        assertThat(sow.isAiApplied()).isFalse();
    }

    // ── budget exhausted → graceful, no throw, zero spend ─────────────────────

    @Test
    void draft_budgetExhausted_gracefulEmptyDraft_zeroSpend() {
        // Zero-budget tenant: the AiUsageRecorder.checkBudget trips (1200) BEFORE any call; the
        // best-effort AI leg degrades to an empty aiApplied=false draft (never throws), and WireMock
        // sees ZERO traffic.
        UUID brokeTenant = UUID.randomUUID();
        seedTenant(brokeTenant, BigDecimal.ZERO, Set.of("proposals"));
        seedAnthropic(brokeTenant);
        String brokeToken = "Bearer " + jwt.mint(seedStaff(brokeTenant));
        stubReply("(should never be called)");

        web.post().uri("/proposals/draft")
                .header("Authorization", brokeToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"notes\":\"Logo and a one-page site.\"}")
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.quote.status").isEqualTo("DRAFT")
                .jsonPath("$.sowDraft.aiApplied").isEqualTo(false);

        // A DRAFT Quote was still materialized for the broke tenant.
        Quote quote = mongo.findOne(new Query(Criteria.where("tenantId").is(brokeTenant)), Quote.class).block();
        assertThat(quote).isNotNull();
        assertThat(quote.getStatus()).isEqualTo(Quote.Status.DRAFT);

        // Zero Anthropic traffic — the budget gate short-circuited before the call.
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    // ── validation: blank notes → 4621 ───────────────────────────────────────

    @Test
    void draft_blankNotes_4621() {
        web.post().uri("/proposals/draft")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"notes\":\"   \"}")
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4621);

        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    // ── module off (per-tenant) → 4620 ───────────────────────────────────────

    @Test
    void draft_moduleNotEnabledForTenant_4620() {
        // A tenant on a proposals-enabled deployment but WITHOUT the module in its enabledModules set.
        UUID nonMember = UUID.randomUUID();
        seedTenant(nonMember, new BigDecimal("5.00"), Set.of()); // no "proposals"
        String nonMemberToken = "Bearer " + jwt.mint(seedStaff(nonMember));

        web.post().uri("/proposals/draft")
                .header("Authorization", nonMemberToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"notes\":\"Anything.\"}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4620);
    }

    // ── AI-10: a cross-tenant contactId is rejected (4622), and nothing is materialized ──

    @Test
    void draft_crossTenantContactId_4622_andNoQuoteCreated() {
        // A contact that belongs to a DIFFERENT tenant — must not be attachable to this tenant's draft.
        UUID otherTenant = UUID.randomUUID();
        UUID foreignContact = seedContact(otherTenant);
        stubReply("(should never be called — validation fails before the AI leg)");

        web.post().uri("/proposals/draft")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"notes\":\"Custom CRM build.\",\"contactId\":\"" + foreignContact + "\"}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4622);

        // Nothing was materialized for the caller's tenant — the FK validation runs before Quote create.
        Quote quote = mongo.findOne(new Query(Criteria.where("tenantId").is(tenantId)), Quote.class).block();
        assertThat(quote).isNull();
        SowDraft sow = mongo.findOne(new Query(Criteria.where("tenantId").is(tenantId)), SowDraft.class).block();
        assertThat(sow).isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private UUID seedContact(UUID tid) {
        UUID id = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(id).tenantId(tid)
                .firstName("Pat").lastName("Client").displayName("Pat Client")
                .build()).block();
        return id;
    }

    private void stubReply(String replyText) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":"
                                + jsonString(replyText) + "}],"
                                + "\"usage\":{\"input_tokens\":300,\"output_tokens\":200}}")));
    }

    /** JSON-encodes a string (quotes + escapes) so a canned reply with quotes/newlines is valid. */
    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }

    private void seedTenant(UUID tid, BigDecimal budget, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("sow-it-" + tid)
                .displayName("SOW IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(budget)
                .build()).block();
    }

    private void seedAnthropic(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private User seedStaff(UUID tid) {
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tid)
                .email("staff-" + tid + "@sow.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        return users.save(staff).block();
    }
}
