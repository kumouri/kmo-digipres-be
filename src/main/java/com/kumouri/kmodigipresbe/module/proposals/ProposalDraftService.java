package com.kumouri.kmodigipresbe.module.proposals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.service.quote.QuoteService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The AI Proposal / SOW generator core (band 4620-4639): from a few lines of discovery notes, Claude
 * (Sonnet) drafts a scoped, line-item-<strong>priced</strong> SOW = a DRAFT
 * {@link Quote} (priced via the UNCHANGED {@link QuoteService#create}) + its {@link SowDraft} prose.
 * "A SOW is a priced Quote with prose."
 *
 * <h2>Shape — an additive sibling of {@code VoicemailExtractionService} / {@code DunningCopyComposer}</h2>
 * Same Anthropic-Messages transport as every other vertical AI caller: per-tenant API key from
 * {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the
 * call + spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url}, default
 * the real Anthropic endpoint, OVERRIDDEN to WireMock in every test — §7). It does <strong>NOT</strong>
 * modify the reused AI core ({@code AnthropicAiAssistService} / {@link AiUsageRecorder}) nor the billing
 * core ({@link Quote} / {@link LineItem} / {@link QuoteService}) — all stay empty-diff vs {@code main};
 * it is a sibling caller that reuses {@link QuoteService#create} unchanged.
 *
 * <h2>Strict JSON → defensive parse → "AI is triage, not truth"</h2>
 * The system prompt instructs the model to return ONLY a JSON object
 * {@code {lineItems:[{description, quantity, unitPrice}], scope, deliverables, assumptions, timeline}}.
 * The parse is <strong>defensive</strong> (the {@code VoicemailExtractionService} / {@code MoleVisionService}
 * posture): it strips code fences / surrounding prose and tolerates missing fields. Crucially, the
 * <em>entire</em> AI leg is wrapped {@code onErrorResume(→ empty draft, aiApplied=false)} — a blank /
 * unparseable answer, a budget-exhausted {@code 1200}, an upstream {@code 1202}, or a missing-key
 * {@code 1203} all degrade to an empty draft (no line items, blank prose, {@code aiApplied=false})
 * rather than throwing. A SOW draft is <strong>always</strong> materialized (the human edits it) — an
 * AI outage never blocks the draft. The defensive parse itself never throws.
 *
 * <h2>What it materializes</h2>
 * The parsed line items map to {@link LineItem}s and a DRAFT {@link Quote} is created through the
 * UNCHANGED {@link QuoteService#create} (its {@code computeTotals} prices it; the Quote is NEVER
 * finalized — it stays {@code DRAFT}). The four prose sections persist to a {@link SowDraft} linked by
 * {@code quoteId}. An advisory {@link DomainEventType#PROPOSAL_DRAFTED} fires (drives no core mutation).
 *
 * <p>Idempotency: a draft is intentionally re-invocable (each call is a fresh proposal — the
 * mole-classify / {@code MoleVisionService} precedent); there is no find-or-create seam and
 * <strong>no {@code switchIfEmpty(create)}</strong>. (Request-level exactly-once, if wanted, is the
 * controller's {@code @IdempotentRoute} + {@code Idempotency-Key} header, not a domain ledger.)
 *
 * <p>Validation ({@code 4621}/400): blank {@code notes} or {@code notes} longer than
 * {@code kmosf.modules.proposals.max-notes-chars} (default 8000) is rejected before any AI spend.
 *
 * <p>Reactive: the JSON parse is trivial CPU (the {@code VoicemailExtractionService} defensive-parse
 * precedent — light, on the Netty loop, never blocking I/O); the Quote create + SowDraft save are
 * reactive Mongo. No blocking call sits on the event loop.
 */
@Slf4j
public class ProposalDraftService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a solutions consultant drafting a Statement of Work (SOW) from a few lines of "
            + "discovery notes for a software / online-presence engagement. Read the notes and produce "
            + "a scoped, line-item-priced SOW. Respond with ONLY a single JSON object — no prose, no "
            + "markdown, no code fences — with EXACTLY these keys: "
            + "\"lineItems\" (an array of objects each with \"description\" (string), \"quantity\" "
            + "(number), and \"unitPrice\" (number, the price per unit in the engagement currency)); "
            + "\"scope\" (a short paragraph describing what the engagement covers); \"deliverables\" "
            + "(a short paragraph or newline-separated list of concrete deliverables); \"assumptions\" "
            + "(a short paragraph of assumptions and out-of-scope caveats the pricing depends on); and "
            + "\"timeline\" (a short paragraph describing the phasing / timeline). Price realistically "
            + "for a small-to-mid engagement. Do NOT invent a client name, dates, or facts not implied "
            + "by the notes. If the notes are too thin to price a line confidently, still produce your "
            + "best-effort estimate. Output ONLY the JSON object.";

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final QuoteService quoteService;
    private final SowDraftRepository sowDrafts;
    private final DomainEventPublisher events;
    private final String houseKey;
    private final String draftModel;
    private final int maxNotesChars;
    private final String systemPromptOverride;

    public ProposalDraftService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            QuoteService quoteService,
            SowDraftRepository sowDrafts,
            DomainEventPublisher events,
            String baseUrl,
            String houseKey,
            String draftModel,
            int maxNotesChars,
            String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.quoteService = quoteService;
        this.sowDrafts = sowDrafts;
        this.events = events;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.draftModel = draftModel;
        this.maxNotesChars = maxNotesChars;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * The drafted SOW: the priced DRAFT {@link Quote} + its {@link SowDraft} prose. Returned by
     * {@link #draft} and the {@code GET /proposals/{id}} read.
     */
    public record DraftResult(Quote quote, SowDraft sowDraft) {
    }

    /**
     * Drafts a SOW from discovery notes. Validates the notes ({@code 4621}/400) → calls Claude
     * best-effort (defensive; never throws on AI failure) → materializes a DRAFT {@link Quote} via the
     * UNCHANGED {@link QuoteService#create} + persists the {@link SowDraft} prose → emits
     * {@link DomainEventType#PROPOSAL_DRAFTED}.
     *
     * @param notes     the discovery notes (required, non-blank, ≤ max-notes-chars)
     * @param contactId optional client contact id (carried onto the Quote + the event payload)
     * @param companyId optional client company id (carried onto the Quote)
     * @param dealId    optional originating deal id (carried onto the Quote)
     * @param currency  optional ISO currency for the Quote (defaults to the Quote's own default, USD)
     */
    public Mono<DraftResult> draft(String notes, UUID contactId, UUID companyId, UUID dealId,
                                   String currency) {
        if (notes == null || notes.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Discovery notes are required to draft a proposal", 4621, 400));
        }
        if (notes.length() > maxNotesChars) {
            return Mono.error(new DigiPresBeException(
                    "Discovery notes exceed the maximum of " + maxNotesChars + " characters", 4621, 400));
        }

        return draftFromAi(notes)
                .flatMap(parsed -> materialize(parsed, contactId, companyId, dealId, currency));
    }

    // ── the best-effort AI leg (never throws — degrades to an empty, aiApplied=false draft) ──

    /**
     * Calls the Anthropic transport and parses the structured SOW JSON, best-effort. The whole leg is
     * {@code onErrorResume}'d: a budget-exhausted {@code 1200}, an upstream {@code 1202}, a missing-key
     * {@code 1203}, or any parse failure degrades to {@link ParsedSow#empty()} ({@code aiApplied=false})
     * — it NEVER throws. AI is triage, not truth.
     */
    private Mono<ParsedSow> draftFromAi(String notes) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, notes))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(parseSow(result.text)))
                .onErrorResume(ex -> {
                    log.debug("Proposal draft: AI leg failed ({}); materializing an empty "
                            + "aiApplied=false draft", ex.toString());
                    return Mono.just(ParsedSow.empty());
                });
    }

    private Mono<String> resolveKey(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .map(IntegrationConnection::getSecrets)
                .mapNotNull(secrets -> secrets == null ? null : secrets.get("apiKey"))
                .filter(k -> k != null && !k.isBlank())
                .switchIfEmpty(Mono.defer(() -> houseKey.isBlank()
                        ? Mono.error(new DigiPresBeException(
                                "No Anthropic API key configured for tenant and no house key set",
                                1203, 412))
                        : Mono.just(houseKey)));
    }

    private Mono<CompletionResult> postMessages(String apiKey, String notes) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", draftModel);
        body.put("max_tokens", 1500);
        body.put("system", systemPrompt());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", "Discovery notes:\n\n" + notes)));
        return http.post()
                .uri("")
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("x-api-key", apiKey)
                .header("anthropic-version", API_VERSION)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(errBody -> Mono.error(new DigiPresBeException(
                                "Anthropic proposal-draft call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(ProposalDraftService::parseAnthropicResponse);
    }

    private static CompletionResult parseAnthropicResponse(JsonNode node) {
        String text = "";
        if (node.path("content").isArray()) {
            StringBuilder sb = new StringBuilder();
            for (JsonNode block : node.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
            text = sb.toString();
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return new CompletionResult(text, inputTokens, outputTokens);
    }

    private String systemPrompt() {
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    // ── defensive parse: model text → ParsedSow (never throws) ──

    /**
     * Parses the model's answer into a {@link ParsedSow}. Tolerant of code fences / surrounding prose
     * (strips to the first {@code {...}} block); a blank / non-JSON / parse-failure / structurally-empty
     * answer degrades to {@link ParsedSow#empty()} ({@code aiApplied=false}). Never throws.
     */
    private ParsedSow parseSow(String text) {
        if (text == null || text.isBlank()) {
            return ParsedSow.empty();
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("Proposal draft: model response was not JSON; using empty draft");
            return ParsedSow.empty();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            if (node == null || node.isMissingNode() || !node.isObject()) {
                return ParsedSow.empty();
            }
            List<LineItem> lineItems = parseLineItems(node.path("lineItems"));
            String scope = textOrNull(node.path("scope"));
            String deliverables = textOrNull(node.path("deliverables"));
            String assumptions = textOrNull(node.path("assumptions"));
            String timeline = textOrNull(node.path("timeline"));
            boolean anyContent = !lineItems.isEmpty()
                    || scope != null || deliverables != null || assumptions != null || timeline != null;
            // aiApplied is true iff the model produced at least one usable field — a parsed-but-empty
            // object still counts as a non-applied draft (a human fills it in).
            return new ParsedSow(lineItems, scope, deliverables, assumptions, timeline, anyContent);
        } catch (Exception ex) {
            log.debug("Proposal draft: JSON parse failed ({}); using empty draft", ex.getMessage());
            return ParsedSow.empty();
        }
    }

    private List<LineItem> parseLineItems(JsonNode arr) {
        List<LineItem> items = new ArrayList<>();
        if (arr == null || !arr.isArray()) {
            return items;
        }
        for (JsonNode el : arr) {
            if (el == null || !el.isObject()) {
                continue;
            }
            String description = textOrNull(el.path("description"));
            BigDecimal quantity = decimalOrNull(el.path("quantity"));
            BigDecimal unitPrice = decimalOrNull(el.path("unitPrice"));
            // Skip a fully-empty row; otherwise default a missing quantity to 1 so a priced line still
            // totals (computeTotals treats a null quantity as 0, which would zero the line).
            if (description == null && quantity == null && unitPrice == null) {
                continue;
            }
            items.add(LineItem.builder()
                    .description(description)
                    .quantity(quantity == null ? BigDecimal.ONE : quantity)
                    .unitPrice(unitPrice == null ? BigDecimal.ZERO : unitPrice)
                    .build());
        }
        return items;
    }

    /** Returns the substring from the first '{' to the last '}', or null if absent. */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String s = node.isValueNode() ? node.asText() : node.toString();
        return (s == null || s.isBlank()) ? null : s;
    }

    private static BigDecimal decimalOrNull(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        if (node.isNumber()) {
            return node.decimalValue();
        }
        // Tolerate a numeric string (e.g. "1500" or "$1,500"): strip non-numeric leading symbols.
        String raw = node.asText();
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String cleaned = raw.replaceAll("[^0-9.\\-]", "");
        if (cleaned.isBlank() || "-".equals(cleaned) || ".".equals(cleaned)) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    // ── materialize: DRAFT Quote (priced) + SowDraft prose + PROPOSAL_DRAFTED ──

    private Mono<DraftResult> materialize(ParsedSow parsed, UUID contactId, UUID companyId,
                                          UUID dealId, String currency) {
        return TenantContextHolder.required().flatMap(ctx -> {
            Quote toCreate = Quote.builder()
                    .contactId(contactId)
                    .companyId(companyId)
                    .dealId(dealId)
                    .lineItems(parsed.lineItems())
                    .build();
            if (currency != null && !currency.isBlank()) {
                toCreate.setCurrency(currency.trim());
            }
            // QuoteService.create is UNCHANGED — it nulls the id, stamps statusChangedAt, runs
            // computeTotals (prices the line items), and saves a DRAFT. Never finalized.
            return quoteService.create(toCreate)
                    .flatMap(quote -> {
                        SowDraft sow = SowDraft.builder()
                                .id(UUID.randomUUID())
                                .tenantId(ctx.tenantId())
                                .quoteId(quote.getId())
                                .scope(parsed.scope())
                                .deliverables(parsed.deliverables())
                                .assumptions(parsed.assumptions())
                                .timeline(parsed.timeline())
                                .aiApplied(parsed.aiApplied())
                                .build();
                        return sowDrafts.save(sow)
                                .doOnSuccess(saved -> publishDrafted(ctx.tenantId(), quote, contactId, parsed))
                                .map(saved -> new DraftResult(quote, saved));
                    });
        });
    }

    private void publishDrafted(UUID tenantId, Quote quote, UUID contactId, ParsedSow parsed) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("quoteId", quote.getId().toString());
        if (contactId != null) {
            payload.put("contactId", contactId.toString());
        }
        payload.put("lineItemCount", parsed.lineItems().size());
        payload.put("aiApplied", parsed.aiApplied());
        events.publish(DomainEvent.of(DomainEventType.PROPOSAL_DRAFTED, tenantId, quote.getId(), payload));
    }

    // ── cost estimate (the VoicemailExtractionService model-aware estimate) ──

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = draftModel != null && draftModel.toLowerCase().contains("haiku");
        BigDecimal inPer = haiku ? HAIKU_INPUT_PER_MILLION : SONNET_INPUT_PER_MILLION;
        BigDecimal outPer = haiku ? HAIKU_OUTPUT_PER_MILLION : SONNET_OUTPUT_PER_MILLION;
        BigDecimal input = inPer.multiply(BigDecimal.valueOf(result.inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal output = outPer.multiply(BigDecimal.valueOf(result.outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return input.add(output);
    }

    /** The parsed structured SOW (the AI leg's output). */
    private record ParsedSow(
            List<LineItem> lineItems,
            String scope,
            String deliverables,
            String assumptions,
            String timeline,
            boolean aiApplied) {

        static ParsedSow empty() {
            return new ParsedSow(List.of(), null, null, null, null, false);
        }
    }

    private record CompletionResult(String text, long inputTokens, long outputTokens) {
    }
}
