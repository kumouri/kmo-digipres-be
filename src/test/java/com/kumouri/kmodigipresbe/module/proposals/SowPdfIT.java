package com.kumouri.kmodigipresbe.module.proposals;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.documenso.DocumensoClient;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.contract.ContractTemplate;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SOW-3 — SowPdfIT: the SOW PDF renderer ({@code GET /proposals/{id}/pdf}) + the send-to-sign reuse.
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic goes to WireMock via {@code kmosf.ai.anthropic.base-url} ({@code @DynamicPropertySource} —
 * the {@code ProposalDraftIT} pattern); {@code DocumensoClient} is {@code @MockitoBean}'d so the
 * send-to-sign path can NEVER reach a live Documenso host. The proposals module is default-OFF
 * ({@code matchIfMissing=false}); this IT opts the deployment in via
 * {@code kmosf.modules.proposals.enabled=true} and the per-tenant membership via
 * {@code Tenant.enabledModules}.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li><strong>PDF render</strong> — draft a SOW (canned Anthropic → priced lines + prose), then
 *       {@code GET /proposals/{id}/pdf} → a non-empty {@code application/pdf} whose extracted text
 *       contains a line-item value AND every prose-section marker (the rendered SOW fuses the priced
 *       Quote half + the prose half);</li>
 *   <li><strong>send-to-sign reuse</strong> — the same SOW DRAFT Quote, flipped to ACCEPTED, flows
 *       through the UNCHANGED {@code ContractService.spawnFromQuote}
 *       ({@code POST /contracts/quotes/{quoteId}/spawn-contract}) to a SOW {@link Contract} carrying the
 *       SOW's {@code quoteId} — proving send-to-sign is pure reuse (no new send code) with ZERO live
 *       Documenso traffic;</li>
 *   <li><strong>module off</strong> — a tenant without the {@code proposals} module → {@code 404} on
 *       {@code GET /proposals/{id}/pdf} (the per-tenant membership gate, {@code 4620}).</li>
 * </ol>
 *
 * <p>Shard-safe: self-clean {@code mongo.remove} {@code @BeforeEach}; no
 * {@code application-test.properties} / {@code build.gradle} shard change.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient(timeout = "PT30S")
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.proposals.enabled=true",
        "kmosf.modules.proposals.draft-model=claude-haiku-4-5"
})
class SowPdfIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-sowpdf-fake";

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

    // §7: the Documenso send seam is mocked so send-to-sign can never reach a live host. (Note that the
    // spawn-from-quote step exercised here does not itself call Documenso — the live send is the
    // separate /send flow — but mocking the client is a belt-and-suspenders no-live-external guarantee.)
    @MockitoBean DocumensoClient documensoClient;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Quote.class).block();
        mongo.remove(new Query(), SowDraft.class).block();
        mongo.remove(new Query(), Contract.class).block();
        mongo.remove(new Query(), ContractTemplate.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, new BigDecimal("5.00"), Set.of("proposals"));
        seedAnthropic(tenantId);
        staffToken = "Bearer " + jwt.mint(seedStaff(tenantId));
    }

    @AfterEach
    void resetMocks() {
        wireMock.resetAll();
    }

    // ── PDF render: priced quote half + prose half in one application/pdf ─────

    @Test
    void draftedSow_pdf_isNonEmptyPdf_withLineItemValueAndProseSections() {
        // 2 × 4000 = 8000 and 1 × 1500 = 1500 → total 9500.00.
        stubReply("{\"lineItems\":["
                + "{\"description\":\"Custom CRM build\",\"quantity\":2,\"unitPrice\":4000},"
                + "{\"description\":\"Discovery and design\",\"quantity\":1,\"unitPrice\":1500}],"
                + "\"scope\":\"Build a custom CRM for the client.\","
                + "\"deliverables\":\"A deployed CRM with onboarding.\","
                + "\"assumptions\":\"Client provides timely feedback.\","
                + "\"timeline\":\"Roughly eight weeks across two phases.\"}");

        UUID quoteId = draftSow("Client wants a custom CRM to replace spreadsheets.");

        byte[] pdf = web.get().uri("/proposals/" + quoteId + "/pdf")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentType(MediaType.APPLICATION_PDF)
                .expectBody(byte[].class)
                .returnResult()
                .getResponseBody();

        assertThat(pdf).isNotNull();
        // Magic header + a reasonable size (a one-page SOW with a table + prose is well over 1 KB).
        assertThat(pdf.length).isGreaterThan(1000);
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.ISO_8859_1)).startsWith("%PDF-");

        // Extract the rendered text and assert both halves landed in the document.
        String text = extractPdfText(pdf);
        assertThat(text).contains("STATEMENT OF WORK");
        // A line-item value from the priced quote half (unit price of the first line).
        assertThat(text).contains("4000");
        // The priced total.
        assertThat(text).contains("9500.00");
        // Every prose-section marker + its content from the SOW half.
        assertThat(text).contains("Scope");
        assertThat(text).contains("Build a custom CRM for the client.");
        assertThat(text).contains("Deliverables");
        assertThat(text).contains("Assumptions");
        assertThat(text).contains("Timeline");
        assertThat(text).contains("Roughly eight weeks across two phases.");
    }

    // ── send-to-sign = pure reuse via ContractService.spawnFromQuote ──────────

    @Test
    void sowDraftQuote_flowsThroughSpawnFromQuote_toSowContract_noLiveSend() {
        stubReply("{\"lineItems\":[{\"description\":\"Engagement\",\"quantity\":1,\"unitPrice\":12000}],"
                + "\"scope\":\"Full engagement.\",\"deliverables\":\"Everything.\","
                + "\"assumptions\":\"Standard.\",\"timeline\":\"Q3.\"}");

        // 1. Draft the SOW → a DRAFT Quote (priced) + SowDraft prose.
        UUID quoteId = draftSow("Net-new engagement, scope it.");

        // 2. A SOW is a priced Quote — send-to-sign is the EXISTING spawnFromQuote path, which requires
        //    the Quote to be ACCEPTED. Flip the drafted DRAFT Quote to ACCEPTED (the human "accept" step).
        mongo.updateFirst(
                new Query(Criteria.where("tenantId").is(tenantId).and("_id").is(quoteId)),
                org.springframework.data.mongodb.core.query.Update.update("status", Quote.Status.ACCEPTED),
                Quote.class).block();

        // 3. Seed an active SOW ContractTemplate (the existing contract-doc generator; the SOW PDF prose
        //    is NOT injected here — see the SOW-3 send-to-sign decision in PHASE-PROGRESS.md).
        UUID templateId = seedActiveSowTemplate();

        // 4. The drafted-then-accepted SOW Quote flows through the UNCHANGED send-to-sign seam.
        web.post().uri("/contracts/quotes/" + quoteId + "/spawn-contract?templateId=" + templateId)
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.id").isNotEmpty()
                .jsonPath("$.kind").isEqualTo("SOW")
                .jsonPath("$.status").isEqualTo("DRAFT")
                .jsonPath("$.quoteId").isEqualTo(quoteId.toString())
                .jsonPath("$.templateId").isEqualTo(templateId.toString());

        // A Contract was created from the SOW Quote (reuse), carrying the SOW's quoteId.
        List<Contract> contracts = mongo.findAll(Contract.class).collectList().block();
        assertThat(contracts).hasSize(1);
        assertThat(contracts.get(0).getQuoteId()).isEqualTo(quoteId);
        assertThat(contracts.get(0).getKind()).isEqualTo(ContractTemplate.Kind.SOW);

        // The variables snapshot copied the priced quote (line items) onto the contract — proving the
        // signable contract carries the priced quote (the SOW prose lives in the SOW PDF for review).
        assertThat(contracts.get(0).getVariables()).containsKey("lineItems");

        // ZERO live Documenso traffic: the spawn path never sends; the client is mocked anyway.
        org.mockito.Mockito.verifyNoInteractions(documensoClient);
    }

    // ── module off (per-tenant) → 404 on the PDF route ────────────────────────

    @Test
    void pdf_moduleNotEnabledForTenant_404() {
        // First draft a real SOW as the enabled tenant so a Quote id exists.
        stubReply("{\"lineItems\":[{\"description\":\"X\",\"quantity\":1,\"unitPrice\":100}],"
                + "\"scope\":\"s\",\"deliverables\":\"d\",\"assumptions\":\"a\",\"timeline\":\"t\"}");
        UUID quoteId = draftSow("Anything.");

        // A different tenant WITHOUT the proposals module → the per-tenant membership gate returns 4620.
        UUID nonMember = UUID.randomUUID();
        seedTenant(nonMember, new BigDecimal("5.00"), Set.of()); // no "proposals"
        String nonMemberToken = "Bearer " + jwt.mint(seedStaff(nonMember));

        web.get().uri("/proposals/" + quoteId + "/pdf")
                .header("Authorization", nonMemberToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4620);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /** Drafts a SOW via the endpoint and returns the created DRAFT Quote's id. */
    private UUID draftSow(String notes) {
        web.post().uri("/proposals/draft")
                .header("Authorization", staffToken)
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("{\"notes\":" + jsonString(notes) + ",\"currency\":\"USD\"}")
                .exchange()
                .expectStatus().isCreated();
        // The created Quote is the only one for this tenant; read it back from Mongo for its id.
        Quote q = mongo.findOne(new Query(Criteria.where("tenantId").is(tenantId)), Quote.class).block();
        assertThat(q).as("draft should have materialized a Quote").isNotNull();
        return q.getId();
    }

    private static String extractPdfText(byte[] pdf) {
        try {
            PdfReader reader = new PdfReader(pdf);
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
            reader.close();
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to extract SOW PDF text", ex);
        }
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

    /** JSON-encodes a string (quotes + escapes) so a canned reply / notes value is valid JSON. */
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
                .id(tid).slug("sowpdf-it-" + tid)
                .displayName("SOW PDF IT").status(Tenant.TenantStatus.ACTIVE)
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
                .email("staff-" + tid + "@sowpdf.test").roles(Set.of("STAFF"))
                .status(User.UserStatus.ACTIVE).build();
        return users.save(staff).block();
    }

    private UUID seedActiveSowTemplate() {
        ContractTemplate t = ContractTemplate.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("SOW Template").bodyTemplate("SOW Body for quote {{quoteId}}")
                .kind(ContractTemplate.Kind.SOW).active(true).build();
        return mongo.save(t).block().getId();
    }
}
