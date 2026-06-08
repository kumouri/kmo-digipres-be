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
import com.kumouri.kmodigipresbe.module.realestate.concierge.ListingConciergeService;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversationRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingDisclosureRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingRepository;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingDisclosureService;
import com.kumouri.kmodigipresbe.module.realestate.service.ListingService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.service.ai.embedding.EmbeddingService;
import com.kumouri.kmodigipresbe.service.ai.rag.RagRetrievalService;
import com.kumouri.kmodigipresbe.service.ai.vector.VectorIndex;
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
 * {@link ConciergeInboundRouter} (the CF-3 inbound webhook reused). RE-2..RE-5 (qualification, booking,
 * Marketing Studio, FE) layer on this.
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

    /**
     * The RE-side inbound-SMS router the {@code smsMode="realestate"} seam delegates to. Correlates the
     * inbound to a listing/conversation, answers grounded (or hands off), replies via Twilio, and persists
     * the turns + citations. Wired into the {@code InboundSmsService} via {@link #conciergeInboundWiring}.
     */
    @Bean
    public ConciergeInboundRouter conciergeInboundRouter(
            ListingRepository listings,
            ListingDisclosureRepository disclosures,
            ConciergeConversationRepository conversations,
            ListingConciergeService conciergeService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.realestate.correlation-ttl-minutes:1440}") long correlationTtlMinutes,
            @Value("${kmosf.realestate.handoff-notify:false}") boolean handoffNotify,
            @Value("${kmosf.realestate.handoff-sms:Good question — I've looped in your agent, who'll "
                    + "follow up with you shortly.}") String handoffSmsBody,
            @Value("${kmosf.realestate.disambiguation-sms:Thanks for reaching out! Which property are you "
                    + "asking about? Reply with the address or MLS#.}") String disambiguationSmsBody) {
        return new ConciergeInboundRouter(listings, disclosures, conversations, conciergeService,
                twilioSmsService, events, correlationTtlMinutes, handoffNotify,
                handoffSmsBody, disambiguationSmsBody);
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
