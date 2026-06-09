package com.kumouri.kmodigipresbe.integration.gbp;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.integration.ReviewSentiment;
import com.kumouri.kmodigipresbe.model.integration.SentimentSource;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Classifies the sentiment of an ingested Google-Business-Profile review (E3 Review Engine — sentiment
 * triage). A <strong>NEW additive sibling</strong> mirroring
 * {@link GbpReplyDraftService}'s Anthropic transport shape — per-tenant API key
 * ({@code IntegrationConnection(anthropic).secrets.apiKey} → {@code kmosf.ai.anthropic.house-key}
 * fallback → {@code 1203}), the {@link AiUsageRecorder} budget gate, the configurable
 * {@code kmosf.ai.anthropic.base-url} (→ WireMock in every test). It does <strong>NOT</strong> modify
 * {@code GbpReplyDraftService} or {@code AnthropicAiAssistService} (those cores stay empty-diff).
 *
 * <h2>Rating-first, AI-refined best-effort (AI is triage, not truth — plan §8)</h2>
 * The <strong>rating-based</strong> classification is always computed and always returned (deterministic,
 * never throws): {@code rating >= 4 → POSITIVE}, {@code == 3 → NEUTRAL}, {@code <= 2 → NEGATIVE},
 * {@code null → NEUTRAL}. For a review that carries a comment, an <strong>optional best-effort</strong>
 * Anthropic call refines it; any failure (budget {@code 1200}, upstream {@code 1202}, missing key
 * {@code 1203}, parse failure) <strong>degrades to the rating-based result</strong> via
 * {@code onErrorResume} — it never throws and never fails the review ingest. The result carries the
 * {@link SentimentSource} ({@code RATING} or {@code AI}).
 */
@Slf4j
@Service
public class ReviewSentimentService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You classify the sentiment of a customer review left for a small local service business. "
            + "Consider the star rating and the written comment together. Reply with ONLY one word, in "
            + "uppercase, and nothing else: POSITIVE, NEUTRAL, or NEGATIVE. POSITIVE = the customer is "
            + "happy/satisfied; NEGATIVE = the customer is unhappy/complaining; NEUTRAL = mixed or "
            + "indifferent. Output only the single word.";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String model;
    private final String systemPromptOverride;
    private final boolean aiRefineEnabled;

    public ReviewSentimentService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.review-engine.sentiment-model:claude-haiku-4-5}") String model,
            @Value("${kmosf.review-engine.sentiment-system-prompt:}") String systemPromptOverride,
            @Value("${kmosf.review-engine.ai-refine-enabled:false}") boolean aiRefineEnabled) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.model = model;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
        this.aiRefineEnabled = aiRefineEnabled;
    }

    /**
     * Classifies the review. Always returns a non-null result (never throws): rating-based always, with
     * an <strong>optional</strong> AI refinement for a commented review only when
     * {@code kmosf.review-engine.ai-refine-enabled=true} (DEFAULT-OFF — so the poller adds ZERO extra
     * Anthropic calls by default, keeping the existing GBP poller behavior byte-identical; the AI refine
     * is opt-in, the §7 / regression-gate-friendly default).
     */
    public Mono<ReviewSentiment.Result> classify(GbpReview review) {
        ReviewSentiment ratingBased = fromRating(review.rating());
        ReviewSentiment.Result fallback = new ReviewSentiment.Result(ratingBased, SentimentSource.RATING);

        boolean hasComment = review.comment() != null && !review.comment().isBlank();
        if (!aiRefineEnabled || !hasComment) {
            return Mono.just(fallback);
        }
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postClassify(key, review))
                .flatMap(result -> usageRecorder
                        .record(result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(parseSentiment(result.text)
                                .map(s -> new ReviewSentiment.Result(s, SentimentSource.AI))
                                .orElse(fallback)))
                .onErrorResume(e -> {
                    log.info("Review sentiment AI refine unavailable (falling back to rating-based): {}",
                            e.getMessage());
                    return Mono.just(fallback);
                });
    }

    /** Rating-based classification (always available, never throws). */
    static ReviewSentiment fromRating(Integer rating) {
        if (rating == null) {
            return ReviewSentiment.NEUTRAL;
        }
        if (rating >= 4) {
            return ReviewSentiment.POSITIVE;
        }
        if (rating == 3) {
            return ReviewSentiment.NEUTRAL;
        }
        return ReviewSentiment.NEGATIVE;
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

    private Mono<CompletionResult> postClassify(String apiKey, GbpReview review) {
        Map<String, Object> body = Map.of(
                "model", model,
                "max_tokens", 16,
                "system", systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride,
                "messages", List.of(Map.of("role", "user", "content", buildUserPrompt(review))));
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
                                "Anthropic sentiment call failed: " + response.statusCode() + " " + errBody,
                                1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(ReviewSentimentService::parseAnthropic);
    }

    private static CompletionResult parseAnthropic(JsonNode node) {
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

    /** Defensive parse: find POSITIVE/NEUTRAL/NEGATIVE in the answer; empty if unrecognized. */
    private static java.util.Optional<ReviewSentiment> parseSentiment(String text) {
        if (text == null) {
            return java.util.Optional.empty();
        }
        String upper = text.toUpperCase(Locale.ROOT);
        if (upper.contains("POSITIVE")) {
            return java.util.Optional.of(ReviewSentiment.POSITIVE);
        }
        if (upper.contains("NEGATIVE")) {
            return java.util.Optional.of(ReviewSentiment.NEGATIVE);
        }
        if (upper.contains("NEUTRAL")) {
            return java.util.Optional.of(ReviewSentiment.NEUTRAL);
        }
        return java.util.Optional.empty();
    }

    private static String buildUserPrompt(GbpReview review) {
        StringBuilder sb = new StringBuilder();
        sb.append("Star rating: ")
                .append(review.rating() != null ? review.rating() + " out of 5" : "(not given)")
                .append('\n');
        sb.append("Review text: ")
                .append(review.comment() != null ? review.comment() : "(no written comment)")
                .append('\n');
        sb.append("\nClassify the sentiment.");
        return sb.toString();
    }

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = model != null && model.toLowerCase(Locale.ROOT).contains("haiku");
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
