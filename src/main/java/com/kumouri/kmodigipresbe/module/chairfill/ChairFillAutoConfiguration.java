package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.WorkflowRuleRepository;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.ai.OfferCopyService;
import com.kumouri.kmodigipresbe.module.chairfill.ai.ReminderCopyService;
import com.kumouri.kmodigipresbe.module.chairfill.automation.ChairFillReminderAutomation;
import com.kumouri.kmodigipresbe.module.chairfill.automation.ReminderLogRepository;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.chairfill.gapfill.GapFillService;
import com.kumouri.kmodigipresbe.module.chairfill.gapfill.WaitlistClaimService;
import com.kumouri.kmodigipresbe.module.chairfill.gapfill.WaitlistMatchService;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntryRepository;
import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOfferRepository;
import com.kumouri.kmodigipresbe.module.chairfill.reviews.LedgerReplyExemplarSource;
import com.kumouri.kmodigipresbe.module.chairfill.reviews.ReplyExemplarSource;
import com.kumouri.kmodigipresbe.module.chairfill.reviews.SalonReviewReplyService;
import com.kumouri.kmodigipresbe.module.chairfill.scoring.NoShowRiskScoringService;
import com.kumouri.kmodigipresbe.integration.gbp.GbpReplyDraftService;
import com.kumouri.kmodigipresbe.integration.twilio.InboundSmsService;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.NoShowScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * ChairFill — the personal-care / salon flagship module. Loaded only when
 * {@code kmosf.modules.chairfill.enabled=true} (CF-1 D3).
 *
 * <p>ChairFill rides the shipped {@link SalonSpaAutoConfiguration salon-spa} module — it reads
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.Booking}s and
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu}s — so a tenant must have BOTH
 * {@code salon-spa} and {@code chairfill} in {@code Tenant.enabledModules}. The beans here are
 * {@link ConditionalOnBean}({@link SalonBookingService}.class): if salon-spa is off on this server,
 * ChairFill silently no-ops (no bean is created), exactly like
 * {@link com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration}'s field-service
 * gate. <strong>Blast radius zero:</strong> with the property absent/false no ChairFill bean exists,
 * and the nightly job + any seeders skip non-{@code chairfill} tenants — Mole / Home-Services /
 * lead-scoring tenants and salon tenants without ChairFill are byte-identically unaffected.
 *
 * <p>Per-PR build-out:
 * <ul>
 *   <li><strong>CF-1 (this PR):</strong> the module skeleton + the no-show risk model
 *       ({@link NoShowRiskScoringService}, the nightly Smile {@code LogisticRegression} fork of
 *       {@code LeadScoringV2Service}), stamping {@code Booking.noShowRisk} on upcoming bookings and
 *       emitting {@code BOOKING_RISK_SCORED}. No outbound comms.</li>
 *   <li><strong>CF-2:</strong> risk-tiered prevention — the {@link RiskTieredPreventionService}
 *       subscriber on {@code BOOKING_RISK_SCORED} (HIGH ⇒ require a deposit via the reused salon
 *       deposit path + an extra confirmation; LOW/MEDIUM ⇒ a single Claude-personalized reminder via
 *       {@link ReminderCopyService}), plus the owner-tunable {@link ChairFillReminderAutomation}
 *       baseline WorkflowRule seeder. TCPA-safe (opt-out tag + per-contact frequency cap) and
 *       best-effort (Claude/SMS/deposit failure degrades, never drops a booking).</li>
 *   <li><strong>CF-3 (this PR):</strong> gap-fill waitlist auto-offer (the double-YES-correct showpiece)
 *       — the {@link GapFillService} subscriber on {@code BOOKING_CANCELLED} (additively emitted by
 *       {@code SalonBookingService.cancel()}) ranks the {@code salon-waitlist} pool by the CF-1 model
 *       <em>inverted</em> ({@link WaitlistMatchService}), Claude drafts a time-boxed offer
 *       ({@link OfferCopyService}), and the first inbound "YES" atomically claims the slot
 *       ({@link WaitlistClaimService} — the slot-level {@code findAndModify} gate), winner → a real
 *       booking via the unchanged {@code SalonBookingService.create}, loser → an apology. The net-new
 *       signature-verified inbound-SMS webhook ({@link InboundSmsService}) carries the YES and a STOP →
 *       {@code sms-opt-out} (closing the CF-2 TCPA follow-up).</li>
 *   <li><strong>CF-4 (this PR):</strong> AI review-reply, salon-generalized (D4) — the
 *       {@link SalonReviewReplyService} drafts an on-brand, salon-voiced reply (a brand-tone system
 *       prompt + RAG-retrieved exemplar past <em>approved</em> replies via the
 *       {@link ReplyExemplarSource}) by calling the unchanged {@code GbpReplyDraftService}'s additive
 *       overload, and parks it DRAFTED in the <strong>same {@code GbpReviewReply} approval queue NMM
 *       uses</strong> (the reused {@code GbpReviewReplyAdminController} approves/skips it — never
 *       auto-posted). The {@link com.kumouri.kmodigipresbe.module.chairfill.controller.SalonReviewReplyController}
 *       paste-in endpoint is the demo path (no live Google OAuth). NMM/GBP stays byte-equivalent
 *       (its single-arg {@code draftReply} call is unchanged; the salon prompt/exemplars are
 *       additive + per-tenant). Best-effort: an exemplar/Claude failure degrades to a generic
 *       on-brand draft.</li>
 *   <li>CF-5: FE surfaces (separate repo).</li>
 * </ul>
 */
@AutoConfiguration(after = SalonSpaAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class ChairFillAutoConfiguration {

    public static final String MODULE_KEY = "chairfill";

    @Bean
    public ModuleDefinition chairFillModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "ChairFill", "0.1.0",
                List.of("NO_SHOW_RISK"));
    }

    /**
     * The nightly no-show-risk scorer. {@link ConditionalOnBean}({@link SalonBookingService}.class)
     * so ChairFill no-ops when the salon-spa module it depends on is not loaded on this server.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public NoShowRiskScoringService noShowRiskScoringService(
            TenantRepository tenantRepository,
            BookingRepository bookingRepository,
            ServiceMenuRepository serviceMenuRepository,
            NoShowScoringJobRepository jobRepository,
            DomainEventPublisher eventPublisher,
            @Value("${kmosf.chairfill.noshow-scoring.high-threshold:" + NoShowRisk.DEFAULT_HIGH_THRESHOLD + "}")
            double highThreshold,
            @Value("${kmosf.chairfill.noshow-scoring.medium-threshold:" + NoShowRisk.DEFAULT_MEDIUM_THRESHOLD + "}")
            double mediumThreshold) {
        return new NoShowRiskScoringService(tenantRepository, bookingRepository, serviceMenuRepository,
                jobRepository, eventPublisher, highThreshold, mediumThreshold);
    }

    // ── CF-2: risk-tiered prevention ──────────────────────────────────────────

    /**
     * The Claude-personalized reminder drafter (CF-2). A sibling of {@code GbpReplyDraftService} —
     * per-tenant Anthropic key + house-key fallback, {@link AiUsageRecorder} budget gate, WireMock-able
     * base-url. Hand-constructed so the {@code @Value}-resolved config lands on the factory params
     * (the salon-spa {@code @Bean} construction pattern; component-scan {@code @Value} would not fire).
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public ReminderCopyService chairFillReminderCopyService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.chairfill.reminder-draft-model:claude-haiku-4-5}") String draftModel,
            @Value("${kmosf.chairfill.reminder-system-prompt:}") String systemPromptOverride) {
        return new ReminderCopyService(webClientBuilder, connections, usageRecorder,
                baseUrl, houseKey, draftModel, systemPromptOverride);
    }

    /**
     * The risk-tiered prevention subscriber (CF-2). Subscribes to {@code BOOKING_RISK_SCORED}; HIGH
     * requires a deposit (reused salon path) + an extra confirmation, LOW/MEDIUM sends one
     * Claude-personalized reminder. TCPA-safe + best-effort (see {@link RiskTieredPreventionService}).
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public RiskTieredPreventionService riskTieredPreventionService(
            DomainEventPublisher eventPublisher,
            TenantRepository tenantRepository,
            BookingRepository bookingRepository,
            ContactRepository contactRepository,
            StaffMemberRepository staffMemberRepository,
            ServiceMenuRepository serviceMenuRepository,
            SalonBookingService salonBookingService,
            ReminderCopyService reminderCopyService,
            TwilioSmsService twilioSmsService,
            ReminderLogRepository reminderLogRepository,
            @Value("${kmosf.chairfill.prevention.deposit-rate:0.25}") BigDecimal depositRate,
            @Value("${kmosf.chairfill.prevention.deposit-min:20.00}") BigDecimal depositMin,
            @Value("${kmosf.chairfill.prevention.brand-tone:}") String brandTone,
            @Value("${kmosf.chairfill.prevention.max-per-contact-per-window:2}") int maxPerContactPerWindow,
            @Value("${kmosf.chairfill.prevention.window-hours:24}") long windowHours) {
        return new RiskTieredPreventionService(eventPublisher, tenantRepository, bookingRepository,
                contactRepository, staffMemberRepository, serviceMenuRepository, salonBookingService,
                reminderCopyService, twilioSmsService, reminderLogRepository, depositRate, depositMin,
                brandTone, maxPerContactPerWindow, windowHours);
    }

    /**
     * The owner-tunable baseline reminder {@link com.kumouri.kmodigipresbe.automation.WorkflowRule}
     * seeder (CF-2 / D5). Seeds a toggleable static SEND_SMS rule on {@code BOOKING_RISK_SCORED} per
     * chairfill tenant; the personalized/deposit logic stays in {@link RiskTieredPreventionService}.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public ChairFillReminderAutomation chairFillReminderAutomation(
            TenantRepository tenantRepository,
            WorkflowRuleRepository workflowRuleRepository) {
        return new ChairFillReminderAutomation(tenantRepository, workflowRuleRepository);
    }

    // ── CF-3: gap-fill waitlist auto-offer (the double-YES-correct showpiece) ──

    /**
     * The Claude-personalized time-boxed offer drafter (CF-3) — a sibling of {@link ReminderCopyService}.
     * Hand-constructed so the {@code @Value}-resolved config lands on the factory params.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public OfferCopyService chairFillOfferCopyService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.chairfill.offer-draft-model:claude-haiku-4-5}") String draftModel,
            @Value("${kmosf.chairfill.offer-system-prompt:}") String systemPromptOverride) {
        return new OfferCopyService(webClientBuilder, connections, usageRecorder,
                baseUrl, houseKey, draftModel, systemPromptOverride);
    }

    /** Ranks the waitlist for a freed slot — the CF-1 no-show model inverted (most-likely-to-show first). */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public WaitlistMatchService chairFillWaitlistMatchService(BookingRepository bookingRepository) {
        return new WaitlistMatchService(bookingRepository);
    }

    /**
     * The double-YES-correct atomic slot claim (CF-3 D2): the slot-level {@code findAndModify} via
     * {@link ReactiveMongoTemplate}, winner → booking via the unchanged {@link SalonBookingService#create},
     * loser → apologetic auto-reply. Confirmation/apology SMS bodies configurable.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public WaitlistClaimService chairFillWaitlistClaimService(
            ReactiveMongoTemplate mongoTemplate,
            WaitlistOfferRepository offerRepository,
            WaitlistEntryRepository entryRepository,
            ContactRepository contactRepository,
            SalonBookingService salonBookingService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher eventPublisher,
            @Value("${kmosf.chairfill.gapfill.confirmation-sms:You're booked! See you soon.}")
            String confirmationTemplate,
            @Value("${kmosf.chairfill.gapfill.apology-sms:Sorry — that slot was just taken. "
                    + "You're still first in line for the next opening!}") String apologyTemplate) {
        return new WaitlistClaimService(mongoTemplate, offerRepository, entryRepository, contactRepository,
                salonBookingService, twilioSmsService, eventPublisher, confirmationTemplate, apologyTemplate);
    }

    /**
     * The gap-fill orchestrator (CF-3) — the {@code @PostConstruct} subscriber on {@code BOOKING_CANCELLED}:
     * rank the waitlist → Claude time-boxed offer (best-effort) → mint {@link com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer}s
     * + send to the top-N. TCPA-safe (opt-out tag + opt-in entries) and best-effort.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public GapFillService chairFillGapFillService(
            DomainEventPublisher eventPublisher,
            TenantRepository tenantRepository,
            WaitlistEntryRepository entryRepository,
            WaitlistOfferRepository offerRepository,
            ContactRepository contactRepository,
            StaffMemberRepository staffMemberRepository,
            ServiceMenuRepository serviceMenuRepository,
            WaitlistMatchService waitlistMatchService,
            OfferCopyService offerCopyService,
            TwilioSmsService twilioSmsService,
            @Value("${kmosf.chairfill.gapfill.max-offers:3}") int maxOffers,
            @Value("${kmosf.chairfill.gapfill.offer-ttl-minutes:10}") long offerTtlMinutes,
            @Value("${kmosf.chairfill.gapfill.brand-tone:}") String brandTone) {
        return new GapFillService(eventPublisher, tenantRepository, entryRepository, offerRepository,
                contactRepository, staffMemberRepository, serviceMenuRepository, waitlistMatchService,
                offerCopyService, twilioSmsService, maxOffers, offerTtlMinutes, brandTone);
    }

    /**
     * The net-new inbound-SMS webhook service (CF-3) — signature-verified (reused
     * {@link com.kumouri.kmodigipresbe.integration.twilio.voice.TwilioRequestValidator} + {@code 4000-4003}),
     * correlates an inbound YES to a pending offer (→ atomic claim) and a STOP to the {@code sms-opt-out}
     * consent tag (closes the CF-2 TCPA follow-up). The {@code TwilioInboundSmsController} is
     * {@code @ConditionalOnProperty}-component-scanned and delegates here.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public InboundSmsService chairFillInboundSmsService(
            IntegrationConnectionRepository connections,
            ContactRepository contactRepository,
            WaitlistClaimService waitlistClaimService) {
        return new InboundSmsService(connections, contactRepository, waitlistClaimService);
    }

    // ── CF-4: AI review-reply, salon-generalized + RAG voice + the reused approval queue ──

    /**
     * The default {@link ReplyExemplarSource} (CF-4 RAG, D4): retrieves the salon's own
     * <strong>approved (POSTED)</strong> {@code GbpReviewReply} rows — the literal corpus of past
     * approved replies — as on-brand voice exemplars, ranked by rating proximity to the new review.
     * Backed by the ledger (not the Atlas vector spine) so it is robust in CI / on a fresh cluster;
     * the {@link ReplyExemplarSource} seam keeps a future vector-backed source a drop-in.
     *
     * <p>{@code @ConditionalOnBean(ReplyExemplarSource.class)} is NOT used here so a deployment may
     * override with its own {@code ReplyExemplarSource} {@code @Bean} (e.g. a vector-backed one).
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public ReplyExemplarSource chairFillReplyExemplarSource(GbpReviewReplyRepository reviewReplies) {
        return new LedgerReplyExemplarSource(reviewReplies);
    }

    /**
     * The salon review-reply drafter + queue service (CF-4). Generalizes the shipped GBP review-reply
     * spine per D4: it calls the unchanged {@link GbpReplyDraftService} (transport/budget/parse/codes
     * reused) with a salon brand-tone system prompt + RAG exemplars, and parks the draft DRAFTED in
     * the same {@code GbpReviewReply} approval queue NMM uses (the reused admin surface
     * approves/skips — never auto-posted). Best-effort drafting (Claude/exemplar failure → a generic
     * on-brand draft). The {@code GbpReplyDraftService} dependency is the always-present
     * component-scanned {@code @Service}; NMM is byte-equivalent (it uses the single-arg overload).
     *
     * @param brandTonePrompt   per-tenant brand-voice hint appended to the salon base system prompt
     * @param exemplarsEnabled  toggle for the RAG-exemplar enrichment (default on; best-effort)
     * @param exemplarLimit     how many past approved replies to ground the draft in
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public SalonReviewReplyService chairFillSalonReviewReplyService(
            GbpReplyDraftService gbpReplyDraftService,
            ReplyExemplarSource replyExemplarSource,
            GbpReviewReplyRepository reviewReplies,
            DomainEventPublisher eventPublisher,
            @Value("${kmosf.chairfill.review-system-prompt:}") String brandTonePrompt,
            @Value("${kmosf.chairfill.review-exemplars-enabled:true}") boolean exemplarsEnabled,
            @Value("${kmosf.chairfill.review-exemplar-count:3}") int exemplarLimit) {
        return new SalonReviewReplyService(gbpReplyDraftService, replyExemplarSource, reviewReplies,
                eventPublisher, brandTonePrompt, exemplarsEnabled, exemplarLimit);
    }
}
