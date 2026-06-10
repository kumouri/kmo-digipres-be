package com.kumouri.kmodigipresbe.module.realestate;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.realestate.concierge.QualificationService;
import com.kumouri.kmodigipresbe.module.realestate.controller.dto.ConciergeConversationDetailDTO;
import com.kumouri.kmodigipresbe.module.realestate.controller.dto.ConciergeConversationSummaryDTO;
import com.kumouri.kmodigipresbe.module.realestate.model.BuyerQualification;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.realestate.model.ConversationState;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
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
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real Estate Concierge RE-5a — the staff-facing concierge conversation read backing the RE-5b
 * transcript + citation viewer + lead panel. Drives {@code GET /realestate/conversations} (list) and
 * {@code GET /realestate/conversations/{id}} (detail) over HTTP ({@code WebTestClient}, the
 * {@code WaitlistBoardIT} JWT-auth pattern), seeding {@link ConciergeConversation}/{@link Contact}/
 * {@link Deal} rows directly via Mongo (the {@code RealEstateQualificationIT} seeding posture). A pure
 * read — no WireMock, no Twilio, no external (RE-5a §7).
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>{@code list} returns the tenant's conversations newest-activity first, projected (id, listingId,
 *       contactId, dealId, state, turnCount) with the {@code leadTier} resolved off the buyer Contact's
 *       {@code leadScore} (HOT here; null for an unscored / contactless thread);</li>
 *   <li>{@code list?listingId=} narrows to one listing's threads;</li>
 *   <li>{@code detail} returns the full transcript (ordered turns), each assistant turn's citations, the
 *       accumulated qualification, and the linked dealId + leadTier;</li>
 *   <li>{@code detail} on a missing / not-owned conversation → 4270/404;</li>
 *   <li>a non-realestate tenant → 1132 module-gate not-enabled (the {@code WaitlistBoardIT} posture);</li>
 *   <li>a non-staff role → 1800 forbidden;</li>
 *   <li>tenant isolation — another tenant's conversation never leaks.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true"
})
class RealEstateConciergeConversationReadIT {

    private static final String BUYER_PHONE = "+16185550200";

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        wipe();
        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("realestate"));

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@re5a.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @AfterEach
    void cleanup() {
        wipe();
    }

    private void wipe() {
        mongo.remove(new Query(), ConciergeConversation.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // ── 1. list: newest-activity first, projected, leadTier resolved ──────────────

    @Test
    void list_returnsConversations_newestFirst_projected_withLeadTier() {
        UUID listingA = UUID.randomUUID();
        UUID listingB = UUID.randomUUID();

        // A fully-materialized thread: buyer contact (HOT), concierge Deal, qualification, 2 turns.
        UUID contactId = seedBuyerContact(LeadScore.TIER_HOT, 0.82);
        Deal deal = seedConciergeDeal(contactId, listingA, new BigDecimal("450000"));
        UUID hotConvId = seedConversation(listingA, contactId, deal.getId(),
                ConversationState.QUALIFYING, qualification(),
                Instant.now().minus(Duration.ofMinutes(2)), twoTurnTranscript());

        // An older, bare thread: no contact yet (ASKING), one buyer turn.
        UUID bareConvId = seedConversation(listingB, null, null,
                ConversationState.ASKING, null,
                Instant.now().minus(Duration.ofMinutes(40)),
                List.of(buyerTurn("Is it still available?",
                        Instant.now().minus(Duration.ofMinutes(40)))));

        List<ConciergeConversationSummaryDTO> rows = listAll(null);

        assertThat(rows).isNotNull();
        // Newest activity first: the HOT thread (2m ago) before the bare thread (40m ago).
        assertThat(rows).extracting(ConciergeConversationSummaryDTO::id)
                .containsExactly(hotConvId, bareConvId);

        ConciergeConversationSummaryDTO hot = rows.get(0);
        assertThat(hot.listingId()).isEqualTo(listingA);
        assertThat(hot.contactId()).isEqualTo(contactId);
        assertThat(hot.dealId()).isEqualTo(deal.getId());
        assertThat(hot.state()).isEqualTo(ConversationState.QUALIFYING);
        assertThat(hot.leadTier()).isEqualTo(LeadScore.TIER_HOT);
        assertThat(hot.turnCount()).isEqualTo(2);
        assertThat(hot.optedOut()).isFalse();
        assertThat(hot.lastActivityAt()).isNotNull();

        ConciergeConversationSummaryDTO bare = rows.get(1);
        assertThat(bare.contactId()).isNull();
        assertThat(bare.dealId()).isNull();
        assertThat(bare.leadTier()).as("no contact → unscored tier is null").isNull();
        assertThat(bare.turnCount()).isEqualTo(1);
    }

    // ── 2. list?listingId= narrows to one listing's threads ───────────────────────

    @Test
    void list_filtersByListingId() {
        UUID listingA = UUID.randomUUID();
        UUID listingB = UUID.randomUUID();
        UUID onA = seedConversation(listingA, null, null, ConversationState.ASKING, null,
                Instant.now().minus(Duration.ofMinutes(5)), List.of());
        seedConversation(listingB, null, null, ConversationState.ASKING, null,
                Instant.now().minus(Duration.ofMinutes(3)), List.of());

        List<ConciergeConversationSummaryDTO> rows = listAll(listingA);
        assertThat(rows).extracting(ConciergeConversationSummaryDTO::id).containsExactly(onA);
    }

    // ── 3. detail: transcript + citations + qualification + dealId + leadTier ─────

    @Test
    void detail_returnsTranscript_citations_qualification_dealAndTier() {
        UUID listingA = UUID.randomUUID();
        UUID contactId = seedBuyerContact(LeadScore.TIER_WARM, 0.55);
        Deal deal = seedConciergeDeal(contactId, listingA, new BigDecimal("450000"));
        UUID convId = seedConversation(listingA, contactId, deal.getId(),
                ConversationState.QUALIFYING, qualification(),
                Instant.now().minus(Duration.ofMinutes(1)), twoTurnTranscript());

        ConciergeConversationDetailDTO detail = web.get()
                .uri("/realestate/conversations/" + convId)
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(ConciergeConversationDetailDTO.class).returnResult().getResponseBody();

        assertThat(detail).isNotNull();
        assertThat(detail.id()).isEqualTo(convId);
        assertThat(detail.listingId()).isEqualTo(listingA);
        assertThat(detail.contactId()).isEqualTo(contactId);
        assertThat(detail.dealId()).isEqualTo(deal.getId());
        assertThat(detail.buyerPhone()).isEqualTo(BUYER_PHONE);
        assertThat(detail.state()).isEqualTo(ConversationState.QUALIFYING);
        assertThat(detail.leadTier()).isEqualTo(LeadScore.TIER_WARM);

        // Transcript: ordered buyer → assistant, the assistant turn carrying its citation.
        assertThat(detail.turns()).hasSize(2);
        ConciergeConversationDetailDTO.TurnDTO t0 = detail.turns().get(0);
        assertThat(t0.role()).isEqualTo(ConciergeTurn.Role.BUYER);
        assertThat(t0.body()).isEqualTo("Does it have a finished basement?");
        assertThat(t0.citations()).isEmpty();

        ConciergeConversationDetailDTO.TurnDTO t1 = detail.turns().get(1);
        assertThat(t1.role()).isEqualTo(ConciergeTurn.Role.ASSISTANT);
        assertThat(t1.handoff()).isFalse();
        assertThat(t1.citations()).hasSize(1);
        ConciergeConversationDetailDTO.CitationDTO cite = t1.citations().get(0);
        assertThat(cite.disclosureType()).isEqualTo("FEATURES");
        assertThat(cite.contentPreview()).contains("finished basement");
        assertThat(cite.score()).isEqualTo(0.91);

        // Qualification accumulated.
        assertThat(detail.qualification()).isNotNull();
        assertThat(detail.qualification().budget()).isEqualByComparingTo("450000");
        assertThat(detail.qualification().timeline()).isEqualTo("60 days");
        assertThat(detail.qualification().intent()).isEqualTo(BuyerQualification.Intent.BUY);
        assertThat(detail.qualification().dealMaterialized()).isTrue();
    }

    // ── 4. detail on a missing / not-owned conversation → 4270/404 ────────────────

    @Test
    void detail_missingConversation_is4270() {
        web.get().uri("/realestate/conversations/" + UUID.randomUUID())
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4270);
    }

    // ── 5. non-realestate tenant → 1132 module-gate not-enabled ───────────────────

    @Test
    void nonRealestateTenant_list_isModuleGated_1132() {
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of()); // drop realestate
        tenants.save(t).block();

        web.get().uri("/realestate/conversations")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    // ── 6. non-staff role → 1800 forbidden ────────────────────────────────────────

    @Test
    void nonStaff_list_isForbidden() {
        // BE-02 StaffAuthorizationWebFilter rejects a non-STAFF principal at the central
        // baseline (1803) before the controller RoleGuard (1800) is reached; still 403.
        User noRole = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("norole@re5a.test")
                .roles(Set.of()).status(User.UserStatus.ACTIVE).build();
        users.save(noRole).block();
        String noRoleToken = "Bearer " + jwt.mint(noRole);

        web.get().uri("/realestate/conversations")
                .header("Authorization", noRoleToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1803);
    }

    // ── 7. tenant isolation — another tenant's conversation never leaks ───────────

    @Test
    void read_isTenantIsolated() {
        UUID mineListing = UUID.randomUUID();
        UUID mine = seedConversation(mineListing, null, null, ConversationState.ASKING, null,
                Instant.now(), List.of());

        // A different realestate tenant with its own conversation.
        UUID other = UUID.randomUUID();
        seedTenant(other, Set.of("realestate"));
        UUID theirs = seedConversationForTenant(other, UUID.randomUUID(), null, null,
                ConversationState.ASKING, null, Instant.now(), List.of());

        // List shows only mine.
        List<ConciergeConversationSummaryDTO> rows = listAll(null);
        assertThat(rows).extracting(ConciergeConversationSummaryDTO::id).containsExactly(mine);

        // Detail on the other tenant's conversation → 4270 (not found for me).
        web.get().uri("/realestate/conversations/" + theirs)
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(4270);
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    private List<ConciergeConversationSummaryDTO> listAll(UUID listingId) {
        String uri = listingId == null
                ? "/realestate/conversations"
                : "/realestate/conversations?listingId=" + listingId;
        return web.get().uri(uri)
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(ConciergeConversationSummaryDTO.class).returnResult().getResponseBody();
    }

    private List<ConciergeTurn> twoTurnTranscript() {
        Instant base = Instant.now().minus(Duration.ofMinutes(3));
        ConciergeTurn buyer = buyerTurn("Does it have a finished basement?", base);
        ConciergeTurn assistant = ConciergeTurn.builder()
                .role(ConciergeTurn.Role.ASSISTANT)
                .body("Yes — the listing notes a finished basement with a rec room.")
                .at(base.plus(Duration.ofSeconds(20)))
                .handoff(false)
                .citations(List.of(ConciergeTurn.TurnCitation.builder()
                        .disclosureId(UUID.randomUUID())
                        .disclosureType("FEATURES")
                        .contentPreview("Spacious finished basement with a rec room and wet bar.")
                        .score(0.91)
                        .build()))
                .build();
        return List.of(buyer, assistant);
    }

    private static ConciergeTurn buyerTurn(String body, Instant at) {
        return ConciergeTurn.builder()
                .role(ConciergeTurn.Role.BUYER).body(body).at(at).build();
    }

    private static BuyerQualification qualification() {
        return BuyerQualification.builder()
                .budget(new BigDecimal("450000"))
                .timeline("60 days")
                .financing("pre-approved")
                .preApproved(true)
                .intent(BuyerQualification.Intent.BUY)
                .dealMaterialized(true)
                .build();
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("re5a-it-" + tid)
                .displayName("Real Estate RE-5a IT Brokerage").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    private UUID seedBuyerContact(String tier, double score) {
        UUID id = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(id).tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName("Listing buyer " + BUYER_PHONE)
                .phones(List.of(PhoneNumber.builder().number(BUYER_PHONE).label("concierge").build()))
                .leadScore(new LeadScore(score, tier, LeadScore.SOURCE_RULES_FALLBACK, Instant.now()))
                .build()).block();
        return id;
    }

    private Deal seedConciergeDeal(UUID contactId, UUID listingId, BigDecimal budget) {
        Map<String, Object> cf = new HashMap<>();
        cf.put(QualificationService.DEAL_SOURCE_KEY, QualificationService.DEAL_SOURCE_CONCIERGE);
        cf.put(QualificationService.DEAL_LISTING_ID_KEY, listingId.toString());
        Deal deal = Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Buyer inquiry — concierge")
                .stage(PipelineStage.NEW)
                .value(budget)
                .primaryContactId(contactId)
                .customFields(cf)
                .build();
        return mongo.save(deal).block();
    }

    private UUID seedConversation(UUID listingId, UUID contactId, UUID dealId,
                                  ConversationState state, BuyerQualification qualification,
                                  Instant lastInboundAt, List<ConciergeTurn> turns) {
        return seedConversationForTenant(tenantId, listingId, contactId, dealId, state, qualification,
                lastInboundAt, turns);
    }

    /**
     * Seed a conversation with an explicit {@code lastInboundAt}. {@code @LastModifiedDate} would stamp
     * {@code updatedAt} on insert; the ordering key the controller sorts by is {@code lastInboundAt},
     * which is a plain field set directly here (deterministic ordering, the {@code WaitlistBoardIT}
     * back-dating intent without needing a re-save since lastInboundAt is not audit-managed).
     */
    private UUID seedConversationForTenant(UUID tid, UUID listingId, UUID contactId, UUID dealId,
                                           ConversationState state, BuyerQualification qualification,
                                           Instant lastInboundAt, List<ConciergeTurn> turns) {
        UUID id = UUID.randomUUID();
        mongo.save(ConciergeConversation.builder()
                .id(id).tenantId(tid)
                .listingId(listingId)
                .contactId(contactId)
                .dealId(dealId)
                .buyerPhone(BUYER_PHONE)
                .state(state)
                .qualification(qualification)
                .turns(turns == null ? List.of() : turns)
                .lastInboundAt(lastInboundAt)
                .build()).block();
        return id;
    }
}
