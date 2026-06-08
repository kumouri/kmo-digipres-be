package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.BuyerQualification;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-2) — the <strong>strict-JSON</strong> buyer-qualification extractor that pulls
 * {@code {budget, timeline, financing, preApproved, intent}} from the buyer↔concierge conversation so far
 * (RE-2 §5 / decision 3). The grounded Q&A (RE-1 {@link ConciergeAnswerService}) and this qualification
 * <em>coexist</em>: a buyer question still gets a cited answer; qualification is extracted alongside.
 *
 * <p>Reuses the exact {@code VoicemailExtractionService.extractRaw} transport shape (the RE-1
 * {@link ConciergeAnswerService} sibling): per-tenant Anthropic key from
 * {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a {@code kmosf.ai.anthropic.house-key}
 * fallback, the {@link AiUsageRecorder} budget gate BEFORE the call + spend record AFTER, and a configurable
 * base-url ({@code kmosf.ai.anthropic.base-url}, overridden to WireMock in tests). It does NOT modify the
 * reused AI core (the {@code 1200/1202/1203} codes are reused, never re-allocated).
 *
 * <p><strong>Defensive / best-effort (RE-2 HARD GATE 3):</strong> exactly the {@code extractRaw} discipline
 * — a blank conversation short-circuits to an empty {@link BuyerQualification} without spending; a
 * blank/fenced/prose-wrapped or non-200/parse-failure answer degrades to an empty result rather than
 * throwing (the caller wraps it {@code onErrorResume} too). AI is triage, not truth — a Claude failure
 * never drops the conversation or corrupts the Deal (advisory {@code 4260}).
 *
 * <p>The strict prompt is the {@code MultiTradeExtractionStrategy} "respond with ONLY a single minified
 * JSON object… do not invent values" discipline, applied to qualification: emit ONLY the JSON, omit a
 * field (or use null) when the buyer hasn't revealed it, never guess a budget/timeline.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code RealEstateAutoConfiguration} so it exists only when the
 * module is enabled.
 */
@Slf4j
public class QualificationExtractionService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You extract a home buyer's qualification details from their text-message conversation with a "
            + "real-estate listing concierge. Read the whole conversation and respond with ONLY a single "
            + "minified JSON object with these optional keys: \"budget\" (a number in US dollars, e.g. "
            + "450000 for \"around 450k\"; omit or null if not stated), \"timeline\" (a short phrase the "
            + "buyer used, e.g. \"60 days\", \"this spring\", \"no rush\"; omit if not stated), "
            + "\"financing\" (a short phrase, e.g. \"pre-approved\", \"cash\", \"needs a lender\"; omit if "
            + "not stated), \"preApproved\" (true/false only if the buyer clearly indicated mortgage "
            + "pre-approval; omit otherwise), and \"intent\" (\"BUY\" or \"SELL\"; omit if unclear). Do NOT "
            + "invent or guess values — omit a key (or use null) when the buyer has not revealed it. Output "
            + "ONLY the JSON object, no prose, no code fences.";

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String model;
    private final String systemPromptOverride;

    public QualificationExtractionService(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.realestate.qualification-model:claude-haiku-4-5}") String model,
            @Value("${kmosf.realestate.qualification-system-prompt:}") String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.model = model;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * Extracts the buyer's qualification from the conversation turns so far. Returns a best-effort
     * {@link BuyerQualification} (fields null when not revealed). A blank conversation short-circuits to an
     * empty result without spending and without requiring a {@code TenantContext}; any AI/parse failure
     * also degrades to an empty result (never throws) — the caller still wraps it {@code onErrorResume}.
     */
    public Mono<BuyerQualification> extract(List<ConciergeTurn> turns) {
        String transcript = renderTranscript(turns);
        if (transcript.isBlank()) {
            return Mono.just(new BuyerQualification());
        }
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, transcript))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(parse(result.text)))
                .onErrorResume(err -> {
                    // Best-effort (RE-2 4260): a budget/upstream/missing-key/parse failure degrades to an
                    // empty qualification — the conversation continues, the Deal is never corrupted.
                    log.warn("RE-2 qualification extraction failed (best-effort, advisory 4260): {}",
                            err.toString());
                    return Mono.just(new BuyerQualification());
                });
    }

    /** Renders the buyer/assistant turns into a compact transcript for the extractor. */
    private static String renderTranscript(List<ConciergeTurn> turns) {
        if (turns == null || turns.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (ConciergeTurn t : turns) {
            if (t.getBody() == null || t.getBody().isBlank()) {
                continue;
            }
            String who = t.getRole() == ConciergeTurn.Role.BUYER ? "Buyer" : "Concierge";
            sb.append(who).append(": ").append(t.getBody().trim()).append('\n');
        }
        return sb.toString().trim();
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

    private Mono<CompletionResult> postMessages(String apiKey, String transcript) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", 256);
        body.put("system", systemPrompt());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", "Conversation so far:\n\n" + transcript)));
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
                                "Anthropic qualification-extraction call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(QualificationExtractionService::parseAnthropicResponse);
    }

    private static CompletionResult parseAnthropicResponse(JsonNode node) {
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
     * Parses the model's strict-JSON answer into a {@link BuyerQualification}, tolerant of code fences and
     * surrounding prose (strips to the first {@code {...}} block — the {@code extractRaw} discipline). Any
     * blank/non-JSON/parse failure degrades to an empty qualification (logged); a present-but-unparseable
     * field is simply left null.
     */
    private BuyerQualification parse(String text) {
        JsonNode node = parseJson(text);
        BuyerQualification.BuyerQualificationBuilder b = BuyerQualification.builder();

        JsonNode budget = node.get("budget");
        if (budget != null && budget.isNumber()) {
            b.budget(budget.decimalValue());
        } else if (budget != null && budget.isTextual() && !budget.asText().isBlank()) {
            BigDecimal parsed = parseBudgetText(budget.asText());
            if (parsed != null) {
                b.budget(parsed);
            }
        }
        textField(node, "timeline").ifPresent(b::timeline);
        textField(node, "financing").ifPresent(b::financing);

        JsonNode preApproved = node.get("preApproved");
        if (preApproved != null && preApproved.isBoolean()) {
            b.preApproved(preApproved.booleanValue());
        }
        textField(node, "intent").ifPresent(v -> {
            try {
                b.intent(BuyerQualification.Intent.valueOf(v.trim().toUpperCase()));
            } catch (IllegalArgumentException ignored) {
                // unknown intent label → leave null (defensive)
            }
        });
        return b.build();
    }

    private static java.util.Optional<String> textField(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v != null && v.isTextual() && !v.asText().isBlank()) {
            return java.util.Optional.of(v.asText().trim());
        }
        return java.util.Optional.empty();
    }

    /** Best-effort parse of a free-text budget the model may emit despite the number instruction. */
    private static BigDecimal parseBudgetText(String raw) {
        String s = raw.toLowerCase().replace(",", "").replace("$", "").trim();
        try {
            boolean k = s.contains("k");
            boolean m = s.contains("m");
            String digits = s.replaceAll("[^0-9.]", "");
            if (digits.isBlank()) {
                return null;
            }
            BigDecimal val = new BigDecimal(digits);
            if (m) {
                val = val.multiply(BigDecimal.valueOf(1_000_000));
            } else if (k) {
                val = val.multiply(BigDecimal.valueOf(1_000));
            }
            return val;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private JsonNode parseJson(String text) {
        if (text == null || text.isBlank()) {
            return objectMapper.createObjectNode();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) {
            log.debug("RE-2 qualification: model response was not JSON; using empty object");
            return objectMapper.createObjectNode();
        }
        try {
            JsonNode node = objectMapper.readTree(text.substring(start, end + 1));
            return node == null || node.isMissingNode() ? objectMapper.createObjectNode() : node;
        } catch (Exception ex) {
            log.debug("RE-2 qualification: JSON parse failed ({}); using empty object", ex.getMessage());
            return objectMapper.createObjectNode();
        }
    }

    private String systemPrompt() {
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = model != null && model.toLowerCase().contains("haiku");
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
