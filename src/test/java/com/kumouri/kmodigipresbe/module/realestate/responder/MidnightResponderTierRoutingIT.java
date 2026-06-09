package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.realestate.concierge.QualificationService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 (Real Estate "Midnight Responder") — tier routing on qualification. Proves the
 * {@link TierRoutingService} {@code LEAD_SCORE_UPDATED} subscriber auto-enrolls a WARM/COLD
 * concierge-sourced realestate lead into the tenant's configured nurture campaign (idempotent on re-fire),
 * no-ops on HOT (the RE-2 hot-handoff owns it), and no-ops for non-concierge / unmapped-tier / no-config.
 *
 * <p>Driven deterministically via the visible-for-test {@link TierRoutingService#handle(DomainEvent)} entry
 * (the {@code RealEstateQualificationIT} pattern — no live event bus + sleep). {@link TwilioSmsService} is a
 * {@code @MockitoBean} so the responder beans construct without a live Twilio. No live external (§7).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true"
})
class MidnightResponderTierRoutingIT {

    private static final String BUYER = "+12145558801";

    @Autowired TierRoutingService tierRoutingService;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private UUID tenantId;
    private UUID warmCampaignId;
    private UUID coldCampaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), MidnightResponderConfig.class).block();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenReturn(Mono.just(true));

        tenantId = UUID.randomUUID();
        warmCampaignId = UUID.randomUUID();
        coldCampaignId = UUID.randomUUID();
        seedTenant(Set.of("realestate", "responder", "nurture"));
        seedCampaign(warmCampaignId, "Warm");
        seedCampaign(coldCampaignId, "Cold");
        seedMidnightConfig(warmCampaignId, coldCampaignId, true);
    }

    // ── WARM → auto-enroll into the warm campaign, idempotent on re-fire ──

    @Test
    void warmTier_enrollsIntoWarmCampaign_andIsIdempotent() {
        UUID contactId = seedBuyerContact(LeadScore.TIER_WARM, 0.5);
        seedConciergeDeal(contactId, new BigDecimal("400000"));

        tierRoutingService.handle(scoreEvent(contactId, 0.5, LeadScore.TIER_WARM)).block();

        List<NurtureEnrollment> enrollments = mongo.findAll(NurtureEnrollment.class).collectList().block();
        assertThat(enrollments).hasSize(1);
        assertThat(enrollments.get(0).getCampaignId()).isEqualTo(warmCampaignId);
        assertThat(enrollments.get(0).getContactId()).isEqualTo(contactId);
        assertThat(enrollments.get(0).getStatus()).isEqualTo(NurtureEnrollmentStatus.ENROLLED);
        assertThat(enrollments.get(0).getBucket()).isEqualTo(DormancyBucket.B);

        // Re-firing the same WARM score does ZERO duplicate enroll (explicit-boolean idempotency).
        tierRoutingService.handle(scoreEvent(contactId, 0.5, LeadScore.TIER_WARM)).block();
        assertThat(mongo.findAll(NurtureEnrollment.class).collectList().block()).hasSize(1);
    }

    // ── COLD → auto-enroll into the cold (long-cadence) campaign ──

    @Test
    void coldTier_enrollsIntoColdCampaign() {
        UUID contactId = seedBuyerContact(LeadScore.TIER_COLD, 0.2);
        seedConciergeDeal(contactId, new BigDecimal("250000"));

        tierRoutingService.handle(scoreEvent(contactId, 0.2, LeadScore.TIER_COLD)).block();

        List<NurtureEnrollment> enrollments = mongo.findAll(NurtureEnrollment.class).collectList().block();
        assertThat(enrollments).hasSize(1);
        assertThat(enrollments.get(0).getCampaignId()).isEqualTo(coldCampaignId);
        assertThat(enrollments.get(0).getBucket()).isEqualTo(DormancyBucket.C);
    }

    // ── HOT → T3 no-ops (the RE-2 hot-handoff owns HOT — exactly one handler per tier) ──

    @Test
    void hotTier_isNoOp_noEnroll() {
        UUID contactId = seedBuyerContact(LeadScore.TIER_HOT, 0.9);
        seedConciergeDeal(contactId, new BigDecimal("600000"));

        tierRoutingService.handle(scoreEvent(contactId, 0.9, LeadScore.TIER_HOT)).block();

        assertThat(mongo.findAll(NurtureEnrollment.class).collectList().block())
                .as("HOT is owned by LeadHandoffService — T3 does not enroll").isEmpty();
    }

    // ── non-concierge Deal → no enroll (scoping correct) ──

    @Test
    void nonConciergeDeal_isNoOp_noEnroll() {
        UUID contactId = seedBuyerContact(LeadScore.TIER_WARM, 0.5);
        seedPlainDeal(contactId, new BigDecimal("400000"));

        tierRoutingService.handle(scoreEvent(contactId, 0.5, LeadScore.TIER_WARM)).block();

        assertThat(mongo.findAll(NurtureEnrollment.class).collectList().block())
                .as("a non-concierge deal is not a tier-route target").isEmpty();
    }

    // ── unmapped tier (null warm campaign in config) → clean no-op ──

    @Test
    void unmappedTier_isNoOp_noEnroll() {
        // Re-seed the config with NO warm campaign mapping.
        mongo.remove(new Query(), MidnightResponderConfig.class).block();
        seedMidnightConfig(null, coldCampaignId, true);
        UUID contactId = seedBuyerContact(LeadScore.TIER_WARM, 0.5);
        seedConciergeDeal(contactId, new BigDecimal("400000"));

        tierRoutingService.handle(scoreEvent(contactId, 0.5, LeadScore.TIER_WARM)).block();

        assertThat(mongo.findAll(NurtureEnrollment.class).collectList().block())
                .as("a null tier→campaign mapping routes nowhere").isEmpty();
    }

    // ── no config row at all → clean no-op ──

    @Test
    void noConfig_isNoOp_noEnroll() {
        mongo.remove(new Query(), MidnightResponderConfig.class).block();
        UUID contactId = seedBuyerContact(LeadScore.TIER_WARM, 0.5);
        seedConciergeDeal(contactId, new BigDecimal("400000"));

        tierRoutingService.handle(scoreEvent(contactId, 0.5, LeadScore.TIER_WARM)).block();

        assertThat(mongo.findAll(NurtureEnrollment.class).collectList().block())
                .as("no MidnightResponderConfig → no routing").isEmpty();
    }

    // ── tenant missing the responder module → no enroll (both-module defense-in-depth) ──

    @Test
    void tenantMissingResponderModule_isNoOp_noEnroll() {
        mongo.remove(new Query(), Tenant.class).block();
        seedTenant(Set.of("realestate", "nurture")); // responder NOT enabled for this tenant
        UUID contactId = seedBuyerContact(LeadScore.TIER_WARM, 0.5);
        seedConciergeDeal(contactId, new BigDecimal("400000"));

        tierRoutingService.handle(scoreEvent(contactId, 0.5, LeadScore.TIER_WARM)).block();

        assertThat(mongo.findAll(NurtureEnrollment.class).collectList().block())
                .as("tenant without the responder module is not routed").isEmpty();
    }

    // ── helpers ──

    private DomainEvent scoreEvent(UUID contactId, double score, String tier) {
        return DomainEvent.of(DomainEventType.LEAD_SCORE_UPDATED, tenantId, contactId,
                Map.of("contactId", contactId.toString(), "score", score, "tier", tier,
                        "source", LeadScore.SOURCE_RULES_FALLBACK));
    }

    private void seedTenant(Set<String> modules) {
        mongo.save(Tenant.builder()
                .id(tenantId).slug("midnight-tier-" + tenantId)
                .displayName("Midnight Tier IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    private void seedCampaign(UUID id, String label) {
        mongo.save(NurtureCampaign.builder()
                .id(id).tenantId(tenantId)
                .name("Midnight " + label + " " + id)
                .active(true)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.B, 0, null, null, null)))
                .steps(List.of())
                .build()).block();
    }

    private void seedMidnightConfig(UUID warm, UUID cold, boolean delegateHandoff) {
        mongo.save(MidnightResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .warmCampaignId(warm).coldCampaignId(cold)
                .delegateHandoffToResponder(delegateHandoff)
                .afterHoursStartHour(8).afterHoursEndHour(18)
                .build()).block();
    }

    private UUID seedBuyerContact(String tier, double score) {
        UUID id = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(id).tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName("Listing buyer " + BUYER)
                .phones(List.of(PhoneNumber.builder().number(BUYER).label("concierge").build()))
                .leadScore(new LeadScore(score, tier, LeadScore.SOURCE_RULES_FALLBACK, Instant.now()))
                .build()).block();
        return id;
    }

    private void seedConciergeDeal(UUID contactId, BigDecimal budget) {
        Map<String, Object> cf = new HashMap<>();
        cf.put(QualificationService.DEAL_SOURCE_KEY, QualificationService.DEAL_SOURCE_CONCIERGE);
        cf.put(QualificationService.DEAL_LISTING_ID_KEY, UUID.randomUUID().toString());
        mongo.save(Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Buyer inquiry — concierge")
                .stage(PipelineStage.NEW)
                .value(budget)
                .primaryContactId(contactId)
                .customFields(cf)
                .build()).block();
    }

    private void seedPlainDeal(UUID contactId, BigDecimal value) {
        mongo.save(Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Plain deal")
                .stage(PipelineStage.NEW)
                .value(value)
                .primaryContactId(contactId)
                .build()).block();
    }
}
