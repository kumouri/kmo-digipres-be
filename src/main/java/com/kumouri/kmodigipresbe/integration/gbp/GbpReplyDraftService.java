package com.kumouri.kmodigipresbe.integration.gbp;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Drafts an on-brand reply to a Google-Business-Profile review via the Anthropic Messages API
 * (NMM GBP review-reply automation). A <strong>new additive service that mirrors
 * {@link com.kumouri.kmodigipresbe.service.ai.AnthropicAiAssistService} exactly</strong> in shape
 * — per-tenant API key from {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with
 * a {@code kmosf.ai.anthropic.house-key} fallback, the {@link AiUsageRecorder} budget gate BEFORE
 * the call + spend record AFTER, and a configurable base-url ({@code kmosf.ai.anthropic.base-url},
 * default the real Anthropic endpoint, OVERRIDDEN to WireMock in every test — §7).
 *
 * <p>It does <strong>NOT</strong> modify {@code AnthropicAiAssistService} (that reused core stays
 * empty-diff vs the base); this is a sibling caller (the Phase-1 {@code VoicemailExtractionService}
 * / Phase-2 {@code MoleVisionService} posture). Its system prompt is a warm, concise, professional
 * small-local-business reply voice that thanks the reviewer, addresses specifics, and — for a low
 * rating — responds graciously and offers to make it right. The prompt is configurable via
 * {@code kmosf.gbp.reply-system-prompt} (blank = the built-in default).
 *
 * <p>Error codes are reused, not re-allocated: the budget gate owns {@code 1200/1201}, the
 * Anthropic upstream non-200 is {@code 1202}, missing-key is {@code 1203}. The poller calls this
 * <em>best-effort</em> ({@code onErrorResume}) so a budget-exhausted {@code 1200} or upstream
 * {@code 1202} leaves the review ledgered (Rob still sees it) without a draft — AI is triage, not
 * truth (plan §8). A blank/whitespace model answer surfaces {@code 1202} too (no usable draft).
 */
@Slf4j
@Service
public class GbpReplyDraftService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You write a short public reply, on behalf of the business owner, to a customer review "
            + "left on the business's Google Business Profile. The business is a small, "
            + "owner-operated local service company. Write in a warm, genuine, concise, professional "
            + "voice — like a real owner who personally cares, not a corporate brand. Thank the "
            + "reviewer by name when one is given, acknowledge something specific they mentioned, and "
            + "keep it to 2-4 sentences. For a positive review, express genuine appreciation and "
            + "invite them back. For a critical or low-star review, respond graciously and without "
            + "defensiveness, take responsibility, and offer to make it right (invite them to get in "
            + "touch directly). Do NOT invent facts, discounts, names, or details not present in the "
            + "review. Do NOT include placeholders, signatures, or markdown — output ONLY the reply "
            + "text itself, nothing else.";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final String houseKey;
    private final String draftModel;
    private final String systemPromptOverride;

    public GbpReplyDraftService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.gbp.draft-model:claude-sonnet-4-6}") String draftModel,
            @Value("${kmosf.gbp.reply-system-prompt:}") String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.draftModel = draftModel;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * Drafts a reply to the given review using the tenant/house default system prompt and no
     * exemplars. Mirrors {@code AnthropicAiAssistService.call}: tenant key → budget gate → POST →
     * record spend → return the trimmed reply text.
     *
     * <p><strong>Byte-equivalent for NMM/GBP:</strong> this is the original single-arg entry point;
     * it delegates to {@link #draftReply(GbpReview, String, java.util.List)} with a {@code null}
     * prompt-override (→ the tenant/house default system prompt, exactly as before) and {@code null}
     * exemplars (→ today's user prompt verbatim). The GBP poller/admin path is unchanged.
     */
    public Mono<String> draftReply(GbpReview review) {
        return draftReply(review, null, null);
    }

    /**
     * Drafts a reply, optionally overriding the system prompt and prepending a few on-brand
     * exemplar past replies to the user message. <strong>Additive, ChairFill CF-4 generalization
     * (D4):</strong> a {@code null}/blank {@code systemPromptOverride} falls back to the configured
     * {@code kmosf.gbp.reply-system-prompt} (or the built-in default) — so the existing GBP/NMM call
     * stays byte-equivalent — while the salon reuse passes a brand-tone prompt + RAG-retrieved
     * exemplars. {@code null}/empty {@code exemplars} reproduces today's user prompt exactly.
     *
     * @param review               the review to reply to
     * @param systemPromptOverride a per-call system prompt ({@code null}/blank = the configured/default)
     * @param exemplars            a few of the salon's own past approved replies for voice grounding
     *                             ({@code null}/empty = none; the GBP/NMM path)
     */
    public Mono<String> draftReply(GbpReview review, String systemPromptOverride,
                                   List<ReplyExemplar> exemplars) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, review, systemPromptOverride, exemplars))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(result.text.trim()));
    }

    /**
     * A single on-brand exemplar — a past review and the approved reply the business gave it — used
     * to ground a new draft in the business's own established voice (ChairFill CF-4 RAG, D4). Carries
     * just enough to teach tone: the prior review's rating + comment and the reply that was posted.
     *
     * @param rating       the prior review's star rating (nullable)
     * @param reviewText   the prior review's comment (nullable)
     * @param approvedReply the reply that was approved/posted for it (the voice signal)
     */
    public record ReplyExemplar(Integer rating, String reviewText, String approvedReply) {
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

    private Mono<CompletionResult> postMessages(String apiKey, GbpReview review,
                                                String systemPromptOverride,
                                                List<ReplyExemplar> exemplars) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", draftModel);
        body.put("max_tokens", 512);
        body.put("system", systemPrompt(systemPromptOverride));
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", buildUserPrompt(review, exemplars))));
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
                                "Anthropic GBP-reply-draft call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .flatMap(this::parseAnthropicResponse);
    }

    private Mono<CompletionResult> parseAnthropicResponse(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        if (node.path("content").isArray()) {
            for (JsonNode block : node.path("content")) {
                if ("text".equals(block.path("type").asText())) {
                    sb.append(block.path("text").asText());
                }
            }
        }
        String text = sb.toString();
        if (text.isBlank()) {
            // No usable reply text — surface as an upstream failure so the poller's best-effort
            // onErrorResume leaves the review ledgered without a draft (never persists a blank reply).
            return Mono.error(new DigiPresBeException(
                    "Anthropic GBP-reply-draft returned an empty reply", 1202, 502));
        }
        long inputTokens = node.path("usage").path("input_tokens").asLong(0L);
        long outputTokens = node.path("usage").path("output_tokens").asLong(0L);
        return Mono.just(new CompletionResult(text, inputTokens, outputTokens));
    }

    /**
     * Resolves the effective system prompt. A per-call {@code override} wins; otherwise the
     * configured {@code kmosf.gbp.reply-system-prompt}; otherwise the built-in default. The
     * single-arg {@code draftReply} passes {@code null} → the original configured/default behavior
     * (NMM/GBP byte-equivalent).
     */
    private String systemPrompt(String override) {
        if (override != null && !override.isBlank()) {
            return override;
        }
        return systemPromptOverride.isBlank() ? DEFAULT_SYSTEM_PROMPT : systemPromptOverride;
    }

    /**
     * Builds the user message. With {@code null}/empty {@code exemplars} this is byte-equivalent to
     * the original GBP/NMM prompt. With exemplars (ChairFill CF-4 RAG) a short "here is how this
     * business has replied to past reviews" block is prepended for on-brand voice grounding.
     */
    private static String buildUserPrompt(GbpReview review, List<ReplyExemplar> exemplars) {
        StringBuilder sb = new StringBuilder();
        if (exemplars != null && !exemplars.isEmpty()) {
            sb.append("Here are a few past reviews this business received and the on-brand replies "
                    + "it gave. Match this voice and style (do NOT copy them verbatim):\n");
            int i = 1;
            for (ReplyExemplar ex : exemplars) {
                if (ex == null || ex.approvedReply() == null || ex.approvedReply().isBlank()) {
                    continue;
                }
                sb.append("\nExample ").append(i++).append(":\n");
                if (ex.rating() != null) {
                    sb.append("- Review (").append(ex.rating()).append(" out of 5): ");
                } else {
                    sb.append("- Review: ");
                }
                sb.append(ex.reviewText() != null && !ex.reviewText().isBlank()
                                ? ex.reviewText() : "(star-only)")
                        .append('\n');
                sb.append("- Reply given: ").append(ex.approvedReply().trim()).append('\n');
            }
            sb.append("\n---\n\n");
        }
        sb.append("Draft a reply to this Google review.\n\n");
        sb.append("Reviewer: ")
                .append(review.reviewerName() != null ? review.reviewerName() : "(anonymous)")
                .append('\n');
        sb.append("Star rating: ")
                .append(review.rating() != null ? review.rating() + " out of 5" : "(not given)")
                .append('\n');
        sb.append("Review text: ")
                .append(review.comment() != null ? review.comment() : "(no written comment — a star-only rating)")
                .append('\n');
        sb.append("\nWrite the reply now.");
        return sb.toString();
    }

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

    private record CompletionResult(String text, long inputTokens, long outputTokens) {
    }
}
