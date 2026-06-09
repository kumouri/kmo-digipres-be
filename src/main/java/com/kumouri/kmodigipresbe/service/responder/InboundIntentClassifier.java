package com.kumouri.kmodigipresbe.service.responder;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import com.kumouri.kmodigipresbe.model.responder.IntentDefinition;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E2 — the reusable Anthropic Messages <strong>text</strong> classifier that maps one inbound free-text
 * message to one of a tenant's configured {@link IntentDefinition intents} (or {@code UNKNOWN}). A
 * structural clone of {@code VoicemailExtractionService} / {@code AiVisionService}, one layer up: the
 * {@link InboundIntentRouter} is the thin caller and this owns the transport.
 *
 * <p>Same shape as the other AI transports: per-tenant API key from
 * {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a
 * {@code kmosf.ai.anthropic.house-key} fallback (1203), the {@link AiUsageRecorder} budget gate BEFORE the
 * call + spend record AFTER (1200/1201), and a configurable base-url ({@code kmosf.ai.anthropic.base-url},
 * default the real Anthropic endpoint, OVERRIDDEN to WireMock in every test — §7). Hand-constructed (a
 * {@code @Bean} in {@code ResponderAutoConfiguration}) so the {@code @Value}-resolved config lands on the
 * factory params (component-scan {@code @Value} would not fire — the ChairFill/salon lesson).
 *
 * <h2>Best-effort by construction — AI is triage, not truth (design directive #2)</h2>
 * {@link #classify} <strong>never throws and never drops the message</strong>:
 * <ul>
 *   <li>a blank message OR an empty configured-intent set short-circuits to
 *       {@link IntentClassification#unknown()} <strong>without spending</strong> (and without requiring a
 *       {@code TenantContext});</li>
 *   <li>a budget-exhausted ({@code 1200}), upstream ({@code 1202}), or parse failure is caught here and
 *       degrades to {@link IntentClassification#unknown()} (logged) — the router then routes
 *       {@code UNKNOWN} to the default handoff so a human follows up.</li>
 * </ul>
 * The model is constrained to emit ONLY one of the configured intent names or {@code "UNKNOWN"}; a label
 * it returns that is not in the configured set is mapped to {@code UNKNOWN} (defensive). Confidence is
 * clamped to {@code [0,1]}.
 *
 * <p>Error codes are reused, not re-allocated: the budget gate owns {@code 1200/1201}, the Anthropic
 * upstream non-200 is {@code 1202}, missing-key is {@code 1203} (all from the AI range). The
 * upstream/budget errors are caught by {@link #classify}'s own {@code onErrorResume} so they never
 * surface as HTTP.
 */
@Slf4j
public class InboundIntentClassifier {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private final WebClient http;
    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String defaultModel;
    private final String defaultSystemPrompt;

    public InboundIntentClassifier(
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            String baseUrl,
            String houseKey,
            String defaultModel,
            String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.objectMapper = objectMapper;
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.defaultModel = (defaultModel == null || defaultModel.isBlank())
                ? "claude-haiku-4-5" : defaultModel;
        this.defaultSystemPrompt = (systemPromptOverride == null || systemPromptOverride.isBlank())
                ? null : systemPromptOverride;
    }

    /**
     * Classify {@code message} against the tenant's {@code intents}. Tenant key → budget gate → POST →
     * record spend → defensive parse. A blank message or empty {@code intents} short-circuits to
     * {@link IntentClassification#unknown()} without spending. Any budget/upstream/parse failure is
     * caught and degrades to {@link IntentClassification#unknown()} — never throws, never drops.
     *
     * @param message              the inbound free-text body (blank → UNKNOWN, no spend)
     * @param intents              the tenant's configured intents (empty → UNKNOWN, no spend)
     * @param modelOverride        optional per-tenant model id (else the configured default)
     * @param systemPromptOverride optional per-tenant system prompt (else the built-in strict prompt)
     */
    public Mono<IntentClassification> classify(String message, List<IntentDefinition> intents,
                                               String modelOverride, String systemPromptOverride) {
        if (message == null || message.isBlank() || intents == null || intents.isEmpty()) {
            return Mono.just(IntentClassification.unknown());
        }
        Set<String> allowed = new HashSet<>();
        for (IntentDefinition d : intents) {
            if (d != null && d.name() != null && !d.name().isBlank()) {
                allowed.add(d.name().trim());
            }
        }
        if (allowed.isEmpty()) {
            return Mono.just(IntentClassification.unknown());
        }
        String model = (modelOverride != null && !modelOverride.isBlank()) ? modelOverride : defaultModel;
        String systemPrompt = resolveSystemPrompt(intents, systemPromptOverride);

        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, message, model, systemPrompt))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(model, result))
                        .thenReturn(parseClassification(result.text, allowed)))
                // Best-effort: a budget (1200), upstream (1202), missing-key (1203), or any other failure
                // degrades to UNKNOWN — the router hands off; the message is never dropped.
                .onErrorResume(e -> {
                    log.warn("Intent classify failed (best-effort, → UNKNOWN): {}", e.getMessage());
                    return Mono.just(IntentClassification.unknown());
                });
    }

    /** The strict classification system prompt, listing the allowed intents + their descriptions. */
    private String resolveSystemPrompt(List<IntentDefinition> intents, String perCallOverride) {
        if (perCallOverride != null && !perCallOverride.isBlank()) {
            return perCallOverride;
        }
        if (defaultSystemPrompt != null) {
            return defaultSystemPrompt;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("You are an intent classifier for an inbound business SMS. Classify the customer's "
                + "message into exactly ONE of the intents below, or \"UNKNOWN\" if none clearly fits. "
                + "Allowed intents:\n");
        for (IntentDefinition d : intents) {
            if (d == null || d.name() == null || d.name().isBlank()) {
                continue;
            }
            sb.append("- ").append(d.name().trim());
            if (d.description() != null && !d.description().isBlank()) {
                sb.append(": ").append(d.description().trim());
            }
            sb.append('\n');
        }
        sb.append("\nRespond with ONLY a JSON object, no prose, no code fences, of the form: "
                + "{\"intent\": \"<one allowed intent or UNKNOWN>\", \"confidence\": <0.0-1.0>, "
                + "\"extractedSlots\": {\"<key>\": \"<value>\"}}. "
                + "Use extractedSlots for any concrete details you can pull from the message (e.g. a "
                + "preferred time, an address, a name); use an empty object if none.");
        return sb.toString();
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

    private Mono<CompletionResult> postMessages(String apiKey, String message, String model,
                                                String systemPrompt) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", model);
        body.put("max_tokens", 512);
        body.put("system", systemPrompt);
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", "Customer message:\n\n" + message)));
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
                                "Anthropic intent-classify call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(this::parseAnthropicResponse);
    }

    private CompletionResult parseAnthropicResponse(JsonNode node) {
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

    /**
     * Parses the model's answer into an {@link IntentClassification}. Tolerant of code fences + prose
     * (strips to the first {@code {...}} block). The {@code intent} is accepted only if it is one of the
     * {@code allowed} names (case-insensitive match → the canonical configured name); anything else
     * (including the literal {@code "UNKNOWN"}, a hallucinated label, or a blank) degrades to
     * {@link IntentClassification#unknown()}. Confidence is clamped to {@code [0,1]}; slots are read as a
     * flat string→string map. Any blank/non-JSON/parse failure degrades to {@code UNKNOWN} (never throws).
     */
    private IntentClassification parseClassification(String text, Set<String> allowed) {
        if (text == null || text.isBlank()) {
            return IntentClassification.unknown();
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("Intent classify: model response was not JSON; using UNKNOWN");
            return IntentClassification.unknown();
        }
        try {
            JsonNode node = objectMapper.readTree(json);
            String rawIntent = textOrNull(node, "intent");
            String canonical = canonicalIntent(rawIntent, allowed);
            if (canonical == null) {
                return IntentClassification.unknown();
            }
            double confidence = clampConfidence(node.path("confidence").asDouble(0.0));
            Map<String, String> slots = readSlots(node.path("extractedSlots"));
            return new IntentClassification(canonical, confidence, slots);
        } catch (Exception ex) {
            log.debug("Intent classify: JSON parse failed ({}); using UNKNOWN", ex.getMessage());
            return IntentClassification.unknown();
        }
    }

    /** Returns the canonical allowed intent name matching {@code raw} (case-insensitive), or null. */
    private static String canonicalIntent(String raw, Set<String> allowed) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String trimmed = raw.trim();
        for (String a : allowed) {
            if (a.equalsIgnoreCase(trimmed)) {
                return a;
            }
        }
        return null;
    }

    private Map<String, String> readSlots(JsonNode slotsNode) {
        if (slotsNode == null || !slotsNode.isObject()) {
            return Map.of();
        }
        Map<String, String> slots = new LinkedHashMap<>();
        slotsNode.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            if (v != null && !v.isNull() && !v.isMissingNode()) {
                String s = v.isValueNode() ? v.asText() : v.toString();
                if (s != null && !s.isBlank()) {
                    slots.put(e.getKey(), s);
                }
            }
        });
        return slots;
    }

    private static double clampConfidence(double raw) {
        if (Double.isNaN(raw) || raw < 0.0) return 0.0;
        return Math.min(raw, 1.0);
    }

    /** Returns the substring from the first '{' to the last '}', or null if absent. */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end < 0 || end < start) return null;
        return text.substring(start, end + 1);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private BigDecimal estimateUsd(String model, CompletionResult result) {
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
