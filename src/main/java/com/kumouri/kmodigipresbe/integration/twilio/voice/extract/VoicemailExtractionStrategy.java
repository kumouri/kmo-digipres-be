package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

import com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailCallbackParams;
import reactor.core.publisher.Mono;

/**
 * Per-tenant voicemail-extraction strategy (HS-1 — Home Services front desk). The seam that lets
 * one BE process serve both an NMM (mole) tenant and a home-services (multi-trade) tenant from the
 * same Twilio voicemail webhook — selected at request time by the tenant's
 * {@code IntegrationConnection(twilio).config.voicemailVertical} value via
 * {@link VoicemailExtractionStrategyResolver}.
 *
 * <p>Exactly the {@code AiVisionService}→{@code MoleVisionService} relationship, one layer up: the
 * strategies are the <strong>thin per-vertical callers</strong> over the shared
 * {@link com.kumouri.kmodigipresbe.integration.twilio.voice.VoicemailExtractionService} Anthropic
 * transport (key resolution + budget gate + POST + defensive parse + the {@code 1200-1203}
 * codes). Each strategy owns only its system prompt, its model choice, and the mapping of the
 * returned JSON into the vertical-agnostic {@link VoicemailLeadDetails} carrier (including the
 * optional DRAFT {@code WorkOrder}).
 *
 * <p>Implementations are stateless {@code @Component}s. {@link #extract} is <strong>best-effort by
 * contract</strong> — it must never propagate an AI budget/upstream failure as a dropped lead; a
 * soft failure degrades to a still-usable {@link VoicemailLeadDetails} (the mole strategy → an
 * empty extraction; the multi-trade strategy → a {@code GENERAL} DRAFT WO from the raw transcript).
 */
public interface VoicemailExtractionStrategy {

    /**
     * The wire value of {@code IntegrationConnection(twilio).config.voicemailVertical} this strategy
     * handles (e.g. {@code "mole-pest"}, {@code "home-services"}). The resolver indexes strategies
     * by this key.
     */
    String verticalKey();

    /**
     * Extracts the structured lead from the transcript and maps it into the vertical-agnostic
     * {@link VoicemailLeadDetails} (common Contact fields + the Activity-payload {@code extractedJson}
     * + an optional DRAFT {@code WorkOrder}). The {@code params} carry the Twilio callback metadata
     * (e.g. {@code CallSid}, {@code From}) a vertical may stamp onto the WorkOrder.
     *
     * <p>Runs under the synthetic {@code TenantContext} {@code TwilioVoicemailService} establishes.
     * Best-effort: a soft AI failure must yield a usable {@link VoicemailLeadDetails}, never an error
     * that drops the lead.
     */
    Mono<VoicemailLeadDetails> extract(String transcript, VoicemailCallbackParams params);
}
