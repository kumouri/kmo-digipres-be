package com.kumouri.kmodigipresbe.module.realestate.marketing;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-4 — Marketing Studio) — the Anthropic caller that drafts the marketing copy:
 * <strong>MLS remarks + N platform-tuned social captions + an email blast</strong>, grounded in the
 * listing facts and the per-photo vision callouts.
 *
 * <p>A sibling of {@code ConciergeAnswerService} / {@code AnthropicAiAssistService} in transport shape —
 * per-tenant API key from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE the call +
 * spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url}, overridden to
 * WireMock in tests). It uses <strong>Sonnet</strong> by default (outbound prose quality matters —
 * {@code kmosf.realestate.marketing-model}, default {@code claude-sonnet-4-6}, the {@code draft-model}
 * choice).
 *
 * <p><strong>Fair-Housing guardrail — layer 1 (the system prompt).</strong> The system prompt forbids
 * protected-class references, steering language, and "ideal for [family/race/religion/…]" framing
 * (FHA §3604(c)). The deterministic {@code FairHousingLint} is layer 2 (applied by the caller on the
 * result). The model is asked to return ONE minified JSON object keyed by channel so the caller can split
 * the pieces deterministically (the {@code MultiTradeExtractionStrategy} strict-JSON discipline).
 *
 * <p><strong>Best-effort.</strong> The {@link #generate} result is the parsed per-channel text map; a
 * blank / non-JSON / parse-failure answer degrades to an <em>empty map</em> (never throws) so the caller
 * persists a partial/empty DRAFTED draft + a degraded flag rather than erroring. The budget gate
 * ({@code 1200}) / upstream non-200 ({@code 1202}) / missing-key ({@code 1203}) errors propagate so the
 * caller can swallow them best-effort (RE-4 {@code 4268}). Error codes are reused, not re-allocated.
 */
@Slf4j
public class MarketingGenerationService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a real-estate marketing copywriter drafting listing marketing for a licensed agent. "
            + "You will be given the listing facts and short per-photo feature notes. Draft compelling, "
            + "accurate marketing copy for several channels.\n\n"
            + "FAIR HOUSING (MANDATORY): You MUST comply with the U.S. Fair Housing Act §3604(c). Do NOT "
            + "reference, prefer, or steer based on any protected class — race, color, religion, national "
            + "origin, sex, familial status (children/families), or disability. Never write phrases like "
            + "\"perfect for families\", \"great for kids\", \"safe neighborhood\", \"ideal for a young "
            + "couple\", \"walking distance to church\", \"no children\", \"adults only\", or anything "
            + "describing the ideal occupant. Describe the PROPERTY and its features, never the kind of "
            + "person who should live there. Do not invent facts not supported by the listing data or the "
            + "photo notes.\n\n"
            + "Respond with ONLY a single minified JSON object and nothing else — no prose, no markdown, "
            + "no code fences. The object MUST have exactly these string keys: \"mlsRemarks\" (the MLS "
            + "public remarks, 2-4 sentences), \"instagram\" (an Instagram caption with a few relevant "
            + "hashtags), \"facebook\" (a Facebook post, a short paragraph), \"x\" (a single concise tweet "
            + "under 280 characters), and \"emailBlast\" (a short email body for the agent's list, with a "
            + "greeting and a call to action). Each value is the finished copy for that channel.";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    private final String houseKey;
    private final String marketingModel;
    private final String systemPromptOverride;

    public MarketingGenerationService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            com.fasterxml.jackson.databind.ObjectMapper objectMapper,
            String baseUrl,
            String houseKey,
            String marketingModel,
            String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.marketingModel = marketingModel;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * Drafts the per-channel marketing copy. Returns a {@link MarketingChannel}→text map (the channels
     * the model populated). A blank/non-JSON/parse-failure answer degrades to an EMPTY map (never throws);
     * budget/upstream/missing-key errors propagate for the caller to swallow best-effort.
     *
     * @param listingFacts a pre-formatted facts block (address, price, beds/baths/sqft, status)
     * @param photoNotes   a pre-formatted per-photo feature-notes block (may be empty)
     */
    public Mono<Map<MarketingChannel, String>> generate(String listingFacts, String photoNotes) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, listingFacts, photoNotes))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(parsePieces(result.text)));
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

    private Mono<CompletionResult> postMessages(String apiKey, String listingFacts, String photoNotes) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", marketingModel);
        body.put("max_tokens", 1500);
        body.put("system", systemPrompt());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", buildUserPrompt(listingFacts, photoNotes))));
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
                                "Anthropic marketing-generation call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(MarketingGenerationService::parseResponse);
    }

    private static CompletionResult parseResponse(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.path("content").isArray()) {
            for (JsonNode block : node.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return new CompletionResult(sb.toString(), inputTokens, outputTokens);
    }

    /**
     * Parses the model's JSON answer into a per-channel text map. Tolerant of code fences / surrounding
     * prose (strips to the first {@code {...}} block); a blank/non-JSON/parse failure yields an EMPTY map
     * (logged) — never throws. Only non-blank channel values are kept.
     */
    private Map<MarketingChannel, String> parsePieces(String text) {
        Map<MarketingChannel, String> pieces = new EnumMap<>(MarketingChannel.class);
        if (text == null || text.isBlank()) {
            return pieces;
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("RE-4: marketing generation response was not JSON; using empty package");
            return pieces;
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            putIfPresent(pieces, MarketingChannel.MLS_REMARKS, node, "mlsRemarks");
            putIfPresent(pieces, MarketingChannel.INSTAGRAM, node, "instagram");
            putIfPresent(pieces, MarketingChannel.FACEBOOK, node, "facebook");
            putIfPresent(pieces, MarketingChannel.X, node, "x");
            putIfPresent(pieces, MarketingChannel.EMAIL_BLAST, node, "emailBlast");
        } catch (Exception ex) {
            log.debug("RE-4: marketing generation JSON parse failed ({}); using empty package",
                    ex.getMessage());
        }
        return pieces;
    }

    private static void putIfPresent(Map<MarketingChannel, String> pieces, MarketingChannel channel,
                                     JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (!v.isMissingNode() && !v.isNull()) {
            String s = v.asText(null);
            if (s != null && !s.isBlank()) {
                pieces.put(channel, s.trim());
            }
        }
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

    private String systemPrompt() {
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    private static String buildUserPrompt(String listingFacts, String photoNotes) {
        StringBuilder sb = new StringBuilder();
        sb.append("LISTING FACTS:\n").append(listingFacts == null ? "" : listingFacts);
        if (photoNotes != null && !photoNotes.isBlank()) {
            sb.append("\n\nPHOTO FEATURE NOTES (woven from the listing photos):\n").append(photoNotes);
        }
        sb.append("\n\nDraft the marketing copy now. Return ONLY the JSON object described in the system "
                + "instructions. Remember: describe the property, never the ideal occupant; no "
                + "protected-class or steering language.");
        return sb.toString();
    }

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = marketingModel != null && marketingModel.toLowerCase().contains("haiku");
        BigDecimal inPer = haiku ? HAIKU_INPUT_PER_MILLION : SONNET_INPUT_PER_MILLION;
        BigDecimal outPer = haiku ? HAIKU_OUTPUT_PER_MILLION : SONNET_OUTPUT_PER_MILLION;
        BigDecimal input = inPer.multiply(BigDecimal.valueOf(result.inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal output = outPer.multiply(BigDecimal.valueOf(result.outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return input.add(output);
    }

    private record CompletionResult(String text, long inputTokens, long outputTokens) {
    }
}
