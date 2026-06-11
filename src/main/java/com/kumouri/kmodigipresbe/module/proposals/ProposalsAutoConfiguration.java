package com.kumouri.kmodigipresbe.module.proposals;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.repository.CompanyRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.service.quote.QuoteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

/**
 * The AI Proposal / SOW generator module (band 4620-4639) — from a few lines of discovery notes,
 * Claude (Sonnet) drafts a scoped, line-item-<strong>priced</strong> SOW: a DRAFT
 * {@link com.kumouri.kmodigipresbe.model.quote.Quote} (priced via the UNCHANGED
 * {@code QuoteService.create}) plus its {@link SowDraft} prose (scope / deliverables / assumptions /
 * timeline). "A SOW is a priced Quote with prose."
 *
 * <h2>Gating — DEFAULT-OFF (a day-one productization bet; per-tenant opt-in)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.proposals", name="enabled",
 * <strong>matchIfMissing=false</strong>)} — the {@link ModuleDefinition} bean (and the
 * {@link ProposalDraftService} bean added in SOW-2) exist <em>only</em> when a deployment explicitly
 * sets {@code kmosf.modules.proposals.enabled=true}. This is the deliberate inverse of the default-ON
 * vertical modules and mirrors the AR {@code ArAutoConfiguration} default-OFF posture exactly: a
 * non-proposals tenant gets no draft service, no {@code /proposals/**} routes (the
 * {@code ProposalDraftController} carries the same gate, so its routes are absent from the OpenAPI
 * spec and 404 when the module is off — the {@code ArAgingController} / {@code NoShowRiskController}
 * precedent), and is byte-identical to before this module existed. Per-tenant membership (once a
 * deployment opts the module in) is then enforced by the {@code TenantModuleRegistry} membership check
 * in the controller (the in-range {@code 4620} parity code).
 *
 * <p>The {@link SowDraftRepository} is component-scanned by {@code @EnableReactiveMongoRepositories}
 * (always present, like {@code DunningLogRepository}); it is simply unused while the module is off.
 *
 * <p>The {@link ProposalDraftService} bean is hand-constructed (not component-scanned) in SOW-2 so the
 * {@code @Value}-resolved Anthropic base-url / house-key / draft-model + max-notes-chars config lands
 * on the factory params (a component-scan {@code @Value} would not fire — the ChairFill / realestate /
 * AR {@code DunningCopyComposer} lesson). The reused AI core ({@code AnthropicAiAssistService} /
 * {@code AiUsageRecorder}) stays empty-diff — the draft service is an additive sibling caller.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.proposals", name = "enabled", matchIfMissing = false)
public class ProposalsAutoConfiguration {

    public static final String MODULE_KEY = "proposals";

    @Bean
    public ModuleDefinition proposalsModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Proposal / SOW Generator", "0.1.0",
                List.of("SOW_DRAFT"));
    }

    /**
     * The SOW draft core (SOW-2). A sibling of {@code VoicemailExtractionService} /
     * {@code DunningCopyComposer} / {@code ConciergeAnswerService} — per-tenant Anthropic key +
     * house-key fallback, {@link AiUsageRecorder} budget gate, WireMock-able base-url. Hand-constructed
     * so the {@code @Value}-resolved config (base-url / house-key / draft-model / max-notes-chars /
     * optional system-prompt override) lands on the factory params (a component-scan {@code @Value}
     * would not fire — the ChairFill / realestate / AR {@code DunningCopyComposer} lesson). It reuses
     * {@link QuoteService#create} UNCHANGED to materialize the priced DRAFT Quote; the reused AI core
     * ({@code AnthropicAiAssistService} / {@link AiUsageRecorder}) and billing core ({@code Quote} /
     * {@code LineItem} / {@code QuoteService}) all stay empty-diff. Defaults to Sonnet
     * ({@code kmosf.modules.proposals.draft-model}) — outbound SOW prose quality matters (the
     * {@code AnthropicAiAssistService.draftModel} convention).
     */
    @Bean
    public ProposalDraftService proposalDraftService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            QuoteService quoteService,
            SowDraftRepository sowDrafts,
            ContactRepository contacts,
            CompanyRepository companies,
            DealRepository deals,
            DomainEventPublisher events,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.modules.proposals.draft-model:claude-sonnet-4-6}") String draftModel,
            @Value("${kmosf.modules.proposals.max-notes-chars:8000}") int maxNotesChars,
            @Value("${kmosf.modules.proposals.draft-system-prompt:}") String systemPromptOverride) {
        return new ProposalDraftService(webClientBuilder, objectMapper, connections, usageRecorder,
                quoteService, sowDrafts, contacts, companies, deals, events, baseUrl, houseKey, draftModel,
                maxNotesChars, systemPromptOverride);
    }
}
