package com.kumouri.kmodigipresbe.module.realestate;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.InboundSmsService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.concierge.ConciergeAnswerService;
import com.kumouri.kmodigipresbe.module.realestate.concierge.ConciergeInboundRouter;
import com.kumouri.kmodigipresbe.module.realestate.concierge.LeadHandoffService;
import com.kumouri.kmodigipresbe.module.realestate.concierge.ListingConciergeService;
import com.kumouri.kmodigipresbe.module.realestate.concierge.QualificationExtractionService;
import com.kumouri.kmodigipresbe.module.realestate.concierge.QualificationService;
import com.kumouri.kmodigipresbe.module.realestate.concierge.ShowingBookingService;
import com.kumouri.kmodigipresbe.module.realestate.marketing.ListingMarketingService;
import com.kumouri.kmodigipresbe.module.realestate.marketing.MarketingGenerationService;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversationRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.HotHandoffLogRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosureRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraftRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingPhotoRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingDisclosureService;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.repository.AttachmentRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
import com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * Real Estate Concierge — flagship #3. Loaded only when {@code kmosf.modules.realestate.enabled=true}
 * (RE-1 §4 decision 7), the literal {@link ChairFillAutoConfiguration} module template.
 *
 * <p><strong>Blast radius zero.</strong> With the property absent/false no RE bean exists; the inbound-SMS
 * router stays in its default (chairfill) mode and NMM / Home-Services / ChairFill / lead-scoring tenants
 * are byte-identically unaffected. The module rides the core CRM / RAG / embedding spine directly (no
 * salon-spa / home-services dependency). Error band: <strong>4250-4259</strong>.
 *
 * <p>Beans are hand-constructed (not component-scanned) so the {@code @Value}-resolved config lands on the
 * factory params (component-scan {@code @Value} would not fire — the ChairFill/salon-spa lesson). The
 * {@code @RestController}s ({@code ListingController}, {@code ListingDisclosureController}) ARE
 * component-scanned but {@code @ConditionalOnProperty}-gated, so they are absent from the OpenAPI spec
 * when the module is off (the {@code NoShowRiskController} precedent).
 *
 * <p>RE-1 build-out: the {@code Listing}/{@code ListingDisclosure} models + disclosure-text indexing
 * (§3 — the module owns the embed+upsert as source type {@code "ListingDisclosure"} with {@code listingId}
 * metadata; the core {@code EmbeddingPipeline} is untouched); the listing-scoped RAG retrieval overload +
 * the strict-grounded {@code ListingConciergeService} (no-hallucination → {@code HANDOFF}); the
 * {@code ConciergeConversation} state; and the {@code smsMode="realestate"} inbound-SMS seam delegating to
 * {@link ConciergeInboundRouter} (the CF-3 inbound webhook reused).
 *
 * <p>RE-2 build-out (additive): the {@link QualificationExtractionService} (strict-JSON Claude extractor,
 * the {@code VoicemailExtractionService} transport shape) + {@link QualificationService} (accumulates the
 * qualification onto the conversation and materializes the buyer {@code Contact} + concierge {@code Deal}
 * the UNCHANGED nightly {@code LeadScoringV2Service} tiers), both wired into the router; and the
 * {@link LeadHandoffService} {@code LEAD_SCORE_UPDATED} subscriber (the CF-2 {@code RiskTieredPreventionService}
 * / {@code RebookingNudgeService} {@code @PostConstruct} pattern) that alerts the agent on a HOT,
 * concierge-sourced lead.
 *
 * <p>RE-3 build-out (additive): the {@link ShowingBookingService} — when the buyer expresses showing intent
 * the router offers demo-grade candidate slots over SMS ({@code OFFERING_SLOTS}); on the buyer's pick it
 * writes a showing {@code Meeting} directly (the {@code CalComWebhookService.reconcileUpsert} projection
 * shape — no live Cal.com call, §7), advances to {@code BOOKED}, logs an {@code Activity(MEETING)}, emits
 * {@code SHOWING_BOOKED}, and texts the confirmation. Wired into the {@link ConciergeInboundRouter}'s
 * state-machine routing. RE-4..RE-5 (Marketing Studio, FE) layer on this.
 */
@AutoConfiguration(after = ChairFillAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
public class RealEstateAutoConfiguration {

    public static final String MODULE_KEY = "realestate";

    @Bean
    public ModuleDefinition realEstateModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Real Estate Concierge", "0.1.0",
                List.of("LISTING", "LISTING_DISCLOSURE", "CONCIERGE_CONVERSATION"));
    }

    // ── Listing CRUD + disclosure-text indexing (RE-1 §3 / §6.2-6.3) ──────────────

    @Bean
    public ListingService realEstateListingService(ListingRepository listings) {
        return new ListingService(listings);
    }

    /**
     * Owns the disclosure CRUD AND the disclosure-text embed+upsert (the §3 crux). Uses the shared
     * {@link EmbeddingService} + {@link VectorIndex} directly (the same {@code embedAndUpsert} shape the
     * core pipeline uses), carrying {@code listingId} metadata so retrieval is listing-scoped.
     */
    @Bean
    public ListingDisclosureService realEstateListingDisclosureService(
            ListingDisclosureRepository disclosures,
            ListingRepository listings,
            EmbeddingService embeddingService,
            VectorIndex vectorIndex,
            DomainEventPublisher events) {
        return new ListingDisclosureService(disclosures, listings, embeddingService, vectorIndex, events);
    }

    // ── The strict-grounded concierge (RE-1 §4 decision 1 / §6.5) ─────────────────

    /**
     * The strict, no-hallucination Anthropic caller — a sibling of {@code OfferCopyService}. Hand-built so
     * the {@code @Value}-resolved key/base-url/model land on the factory params.
     */
    @Bean
    public ConciergeAnswerService conciergeAnswerService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.realestate.concierge-model:claude-haiku-4-5}") String conciergeModel,
            @Value("${kmosf.realestate.concierge-system-prompt:}") String systemPromptOverride) {
        return new ConciergeAnswerService(webClientBuilder, connections, usageRecorder,
                baseUrl, houseKey, conciergeModel, systemPromptOverride);
    }

    /**
     * The strict-grounded answerer: listing-scoped retrieval → no-chunks-short-circuit → strict Claude →
     * {@code HANDOFF} detection → cited answer. The {@code retrieval-top-k} is larger than the default
     * (the in-memory listing filter narrows it down).
     */
    @Bean
    public ListingConciergeService listingConciergeService(
            RagRetrievalService retrieval,
            ConciergeAnswerService answerService,
            @Value("${kmosf.realestate.retrieval-top-k:12}") int retrievalTopK) {
        return new ListingConciergeService(retrieval, answerService, retrievalTopK);
    }

    // ── RE-2 qualification (strict-JSON extract → accumulate → materialize Deal) ──

    /**
     * RE-2 — the strict-JSON buyer-qualification extractor (a sibling of {@link ConciergeAnswerService},
     * the {@code VoicemailExtractionService.extractRaw} transport shape). Hand-built so the
     * {@code @Value}-resolved key/base-url/model land on the factory params.
     */
    @Bean
    public QualificationExtractionService qualificationExtractionService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.realestate.qualification-model:claude-haiku-4-5}") String qualificationModel,
            @Value("${kmosf.realestate.qualification-system-prompt:}") String systemPromptOverride) {
        return new QualificationExtractionService(webClientBuilder, objectMapper, connections, usageRecorder,
                baseUrl, houseKey, qualificationModel, systemPromptOverride);
    }

    /**
     * RE-2 — accumulates the qualification onto the conversation and materializes the buyer {@code Contact}
     * + a concierge-sourced {@code Deal} (stage NEW, value=budget, {@code customFields.source="concierge"}
     * + {@code listingId}). The UNCHANGED nightly {@code LeadScoringV2Service} then tiers that Deal — RE-2
     * adds no ML and does not touch the scorer.
     */
    @Bean
    public QualificationService realEstateQualificationService(
            ContactRepository contacts,
            DealRepository deals,
            DomainEventPublisher events) {
        return new QualificationService(contacts, deals, events);
    }

    /**
     * The RE-side inbound-SMS router the {@code smsMode="realestate"} seam delegates to. Correlates the
     * inbound to a listing/conversation, answers grounded (or hands off), replies via Twilio, persists the
     * turns + citations (RE-1), and runs the RE-2 qualification step (best-effort) afterwards. Wired into
     * the {@code InboundSmsService} via {@link #conciergeInboundSmsWiring}.
     */
    @Bean
    public ConciergeInboundRouter conciergeInboundRouter(
            ListingRepository listings,
            ListingDisclosureRepository disclosures,
            ConciergeConversationRepository conversations,
            ListingConciergeService conciergeService,
            QualificationExtractionService qualificationExtraction,
            QualificationService qualificationService,
            ShowingBookingService showingBookingService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.realestate.correlation-ttl-minutes:1440}") long correlationTtlMinutes,
            @Value("${kmosf.realestate.handoff-notify:false}") boolean handoffNotify,
            @Value("${kmosf.realestate.handoff-sms:Good question — I've looped in your agent, who'll "
                    + "follow up with you shortly.}") String handoffSmsBody,
            @Value("${kmosf.realestate.disambiguation-sms:Thanks for reaching out! Which property are you "
                    + "asking about? Reply with the address or MLS#.}") String disambiguationSmsBody,
            // Security fix BE-12 — default ON: a Fair-Housing-flagged AI answer is suppressed + handed off.
            @Value("${kmosf.realestate.fair-housing-block:true}") boolean fairHousingBlock) {
        return new ConciergeInboundRouter(listings, disclosures, conversations, conciergeService,
                qualificationExtraction, qualificationService, showingBookingService, twilioSmsService, events,
                correlationTtlMinutes, handoffNotify, handoffSmsBody, disambiguationSmsBody, fairHousingBlock);
    }

    // ── RE-3 showing booking (offer slots over SMS → write a Meeting projection) ──

    /**
     * RE-3 — the showing-booking service the router delegates to when the buyer expresses showing intent
     * (offer slots → {@code OFFERING_SLOTS}) and when a slot pick arrives (write a {@code Meeting} → {@code
     * BOOKED} → confirm). The demo writes the {@code Meeting} projection DIRECTLY (the
     * {@code CalComWebhookService.reconcileUpsert} shape — no live Cal.com call, §7); production flips to a
     * live Cal.com booking + the shipped webhook reconcile (idempotent on {@code calComBookingUid}) with no
     * concierge change. Slot offering is demo-grade deterministic generation (the production path reads live
     * Cal.com availability — decision 4); the offer/confirmation copy is deterministic templating (no model
     * call), so the RE-1 grounded path's model-call behavior stays byte-identical.
     */
    @Bean
    public ShowingBookingService showingBookingService(
            ConciergeConversationRepository conversations,
            com.kumouri.kmodigipresbe.repository.MeetingRepository meetings,
            ContactRepository contacts,
            com.kumouri.kmodigipresbe.service.ActivityCrudService activityCrudService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.realestate.showing-slot-count:2}") int slotCount,
            @Value("${kmosf.realestate.showing-slot-duration-minutes:30}") int slotDurationMinutes,
            @Value("${kmosf.realestate.showing-slot-hours:14,16}") List<Integer> slotHours) {
        return new ShowingBookingService(conversations, meetings, contacts, activityCrudService,
                twilioSmsService, events, slotCount, slotDurationMinutes, slotHours);
    }

    // ── RE-4 Marketing Studio (vision captions + Sonnet copy + Fair-Housing lint) ─

    /**
     * RE-4 — the Anthropic marketing generator (a sibling of {@link ConciergeAnswerService} /
     * {@code AnthropicAiAssistService}, the {@code draft}-style Sonnet caller). Hand-built so the
     * {@code @Value}-resolved key/base-url/model land on the factory params. Defaults to Sonnet
     * ({@code kmosf.realestate.marketing-model}) — outbound prose quality matters.
     */
    @Bean
    public MarketingGenerationService marketingGenerationService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            ObjectMapper objectMapper,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.realestate.marketing-model:claude-sonnet-4-6}") String marketingModel,
            @Value("${kmosf.realestate.marketing-system-prompt:}") String systemPromptOverride) {
        return new MarketingGenerationService(webClientBuilder, connections, usageRecorder, objectMapper,
                baseUrl, houseKey, marketingModel, systemPromptOverride);
    }

    /**
     * RE-4 — the Marketing Studio orchestrator: listing-photo intake (store bytes via the shared
     * {@link FileStorageService} + a generic LISTING {@code Attachment} + a {@code ListingPhoto}),
     * generate (caption each photo via the UNCHANGED {@link AiVisionService#extract} + Sonnet draft +
     * the deterministic Fair-Housing lint → a DRAFTED draft), and the draft → approve / skip queue (the
     * GBP review-reply posture — NEVER auto-published). Vision uses Sonnet by default
     * ({@code kmosf.realestate.marketing-vision-model}).
     */
    @Bean
    public ListingMarketingService listingMarketingService(
            ListingRepository listings,
            ListingPhotoRepository photos,
            ListingMarketingDraftRepository drafts,
            AttachmentRepository attachments,
            FileStorageService storage,
            AiVisionService visionService,
            MarketingGenerationService generationService,
            DomainEventPublisher events,
            @Value("${kmosf.realestate.marketing-vision-model:claude-sonnet-4-5}") String visionModel) {
        return new ListingMarketingService(listings, photos, drafts, attachments, storage, visionService,
                generationService, events, visionModel);
    }

    // ── RE-2 hot-handoff (LEAD_SCORE_UPDATED subscriber) ─────────────────────────

    /**
     * RE-2 — the hot-handoff subscriber. A {@code @PostConstruct} listener on the UNCHANGED scorer's
     * {@code LEAD_SCORE_UPDATED} (the CF-2 {@code RiskTieredPreventionService} / {@code RebookingNudgeService}
     * pattern): for a HOT, concierge-sourced realestate {@code Deal} it alerts the agent (best-effort SMS +
     * email to the per-tenant {@code IntegrationConnection(twilio).config.notifyPhone/notifyEmail}) and
     * emits {@code CONCIERGE_HOT_HANDOFF}, idempotent per {@code (tenant, deal)} via {@code HotHandoffLog}.
     * Module-gated + tenant-rechecked → a hard no-op for non-realestate / non-HOT / non-concierge updates.
     */
    @Bean
    public LeadHandoffService leadHandoffService(
            DomainEventPublisher events,
            TenantRepository tenants,
            ContactRepository contacts,
            DealRepository deals,
            HotHandoffLogRepository handoffLog,
            IntegrationConnectionRepository connections,
            TwilioSmsService twilioSmsService,
            EmailService emailService,
            @Value("${kmosf.realestate.handoff-notify:false}") boolean handoffNotify,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        return new LeadHandoffService(events, tenants, contacts, deals, handoffLog, connections,
                twilioSmsService, emailService, handoffNotify, notifyFromAddress);
    }

    // ── Inbound-SMS seam wiring (RE-1 §6.6) ───────────────────────────────────────

    /**
     * For a <strong>pure-realestate</strong> deployment (chairfill off), contribute the
     * {@link InboundSmsService} so the shared inbound webhook still exists. {@code @ConditionalOnMissingBean}
     * means ChairFill's bean wins when both modules are on (it carries the YES claim path; the realestate
     * seam is wired onto it below either way). Evaluated after {@link ChairFillAutoConfiguration} via the
     * {@code @AutoConfiguration(after=...)} ordering.
     */
    @Bean
    @ConditionalOnMissingBean(InboundSmsService.class)
    public InboundSmsService realEstateInboundSmsService(
            IntegrationConnectionRepository connections,
            ContactRepository contacts) {
        return new InboundSmsService(connections, contacts);
    }

    /**
     * Wires the {@link ConciergeInboundRouter} onto whichever {@link InboundSmsService} bean is present
     * (ChairFill's or the realestate fallback above), flipping the {@code smsMode="realestate"} seam live.
     * Returns a tiny marker; the side effect is the setter call at singleton init. Without this, the seam
     * would always see a null router and never delegate.
     */
    @Bean
    public ConciergeInboundSmsWiring conciergeInboundSmsWiring(
            InboundSmsService inboundSmsService,
            ConciergeInboundRouter conciergeInboundRouter) {
        inboundSmsService.setConciergeRouter(conciergeInboundRouter);
        return new ConciergeInboundSmsWiring();
    }

    /** Marker for the {@link #conciergeInboundSmsWiring} side-effecting wiring bean. */
    public static final class ConciergeInboundSmsWiring {
    }
}
