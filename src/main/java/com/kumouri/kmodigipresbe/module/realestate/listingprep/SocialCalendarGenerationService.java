package com.kumouri.kmodigipresbe.module.realestate.listingprep;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (T10 — Listing Prep Studio) — the Anthropic caller that drafts the genuine net-new
 * over RE-4: a <strong>4-week social calendar</strong>, a <em>dated, scheduled sequence</em> of social posts
 * (vs RE-4's single ad-hoc caption per channel), grounded in the listing facts and the per-photo vision
 * callouts.
 *
 * <p>A sibling of {@code MarketingGenerationService} in transport shape — per-tenant API key from
 * {@code IntegrationConnection(provider="anthropic").secrets.apiKey} with a {@code kmosf.ai.anthropic.house-key}
 * fallback, the {@link AiUsageRecorder} budget gate BEFORE the call + spend record AFTER, and a configurable
 * base-url ({@code kmosf.ai.anthropic.base-url}, overridden to WireMock in tests). It uses <strong>Sonnet</strong>
 * by default (outbound prose quality matters — {@code kmosf.realestate.calendar-model}).
 *
 * <p><strong>Fair-Housing guardrail — layer 1 (the system prompt).</strong> The system prompt forbids
 * protected-class references and steering language (FHA §3604(c)); the deterministic {@code FairHousingLint} is
 * layer 2 (applied by the orchestrator over each generated post — a flagged post is held + safe-substituted,
 * never emitted). The model returns a JSON <strong>array</strong> of post objects so the caller can map them to
 * real dates deterministically.
 *
 * <p><strong>Dates are never model-hallucinated.</strong> Each post object carries a {@code week} (1-4) and a
 * {@code dayOffset} (0-27 from the listing's prep start date); the <em>orchestrator</em> maps those to real
 * {@link java.time.LocalDate}s. The model picks the cadence/spread; the calendar dates are computed.
 *
 * <p><strong>Best-effort.</strong> A blank / non-JSON / parse-failure answer degrades to an <em>empty
 * list</em> (never throws) so the caller persists a partial/empty DRAFTED pack + a degraded flag. The budget
 * gate ({@code 1200}) / upstream non-200 ({@code 1202}) / missing-key ({@code 1203}) errors propagate so the
 * caller can swallow them best-effort (T10 {@code 4463}). Error codes are reused, not re-allocated.
 */
@Slf4j
public class SocialCalendarGenerationService {

    private static final String PROVIDER = "anthropic";
    private static final String API_VERSION = "2023-06-01";
    private static final BigDecimal HAIKU_INPUT_PER_MILLION = new BigDecimal("0.80");
    private static final BigDecimal HAIKU_OUTPUT_PER_MILLION = new BigDecimal("4.00");
    private static final BigDecimal SONNET_INPUT_PER_MILLION = new BigDecimal("3.00");
    private static final BigDecimal SONNET_OUTPUT_PER_MILLION = new BigDecimal("15.00");

    /** The calendar's social channels (the description + email are RE-4's; the calendar is social-only). */
    private static final List<MarketingChannel> CALENDAR_CHANNELS =
            List.of(MarketingChannel.INSTAGRAM, MarketingChannel.FACEBOOK, MarketingChannel.X);

    private static final String DEFAULT_SYSTEM_PROMPT =
            "You are a real-estate marketing copywriter building a FOUR-WEEK social media CALENDAR for a "
            + "licensed agent's new listing. You will be given the listing facts and short per-photo feature "
            + "notes. Plan a varied, engaging sequence of social posts spread across 4 weeks (a launch post, "
            + "feature highlights, an open-house style nudge, a price/value angle, a final call — vary the "
            + "framing across posts; do not repeat the same copy).\n\n"
            + "FAIR HOUSING (MANDATORY): You MUST comply with the U.S. Fair Housing Act §3604(c). Do NOT "
            + "reference, prefer, or steer based on any protected class — race, color, religion, national "
            + "origin, sex, familial status (children/families), or disability. Never write phrases like "
            + "\"perfect for families\", \"great for kids\", \"safe neighborhood\", \"ideal for a young "
            + "couple\", \"walking distance to church\", \"no children\", \"adults only\", or anything "
            + "describing the ideal occupant. Describe the PROPERTY and its features, never the kind of "
            + "person who should live there. Do not invent facts not supported by the listing data or the "
            + "photo notes.\n\n"
            + "Respond with ONLY a single minified JSON object and nothing else — no prose, no markdown, no "
            + "code fences. The object MUST have exactly one key \"posts\" whose value is a JSON ARRAY of post "
            + "objects. Each post object MUST have exactly these keys: \"week\" (an integer 1-4), \"dayOffset\" "
            + "(an integer 0-27, days from the listing's launch date — spread the posts across the four weeks), "
            + "\"channel\" (one of \"instagram\", \"facebook\", \"x\"), and \"copy\" (the finished post copy "
            + "for that channel, with a few relevant hashtags for instagram and a concise tweet under 280 "
            + "characters for x). Produce a balanced calendar across the requested number of posts per week.";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final AiUsageRecorder usageRecorder;
    private final ObjectMapper objectMapper;
    private final String houseKey;
    private final String calendarModel;
    private final String systemPromptOverride;

    public SocialCalendarGenerationService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            ObjectMapper objectMapper,
            String baseUrl,
            String houseKey,
            String calendarModel,
            String systemPromptOverride) {
        this.http = webClientBuilder.baseUrl(baseUrl).build();
        this.connections = connections;
        this.usageRecorder = usageRecorder;
        this.objectMapper = objectMapper;
        this.houseKey = houseKey == null ? "" : houseKey;
        this.calendarModel = calendarModel;
        this.systemPromptOverride = systemPromptOverride == null ? "" : systemPromptOverride;
    }

    /**
     * Drafts the 4-week social calendar. Returns the parsed posts (each carrying a week/dayOffset/channel/copy
     * the orchestrator maps to a real date + lints). A blank/non-JSON/parse-failure answer degrades to an EMPTY
     * list (never throws); budget/upstream/missing-key errors propagate for the caller to swallow best-effort.
     *
     * @param listingFacts a pre-formatted facts block (address, price, beds/baths/sqft, status)
     * @param photoNotes   a pre-formatted per-photo feature-notes block (may be empty)
     * @param postsPerWeek the requested posts-per-week target (the model balances around this)
     */
    public Mono<List<CalendarPost>> generate(String listingFacts, String photoNotes, int postsPerWeek) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveKey(ctx.tenantId()))
                .flatMap(key -> usageRecorder.checkBudget().thenReturn(key))
                .flatMap(key -> postMessages(key, listingFacts, photoNotes, postsPerWeek))
                .flatMap(result -> usageRecorder.record(
                                result.inputTokens, result.outputTokens, estimateUsd(result))
                        .thenReturn(parsePosts(result.text)));
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

    private Mono<CompletionResult> postMessages(String apiKey, String listingFacts, String photoNotes,
                                                int postsPerWeek) {
        Map<String, Object> body = new HashMap<>();
        body.put("model", calendarModel);
        body.put("max_tokens", 2000);
        body.put("system", systemPrompt());
        body.put("messages", List.of(Map.of(
                "role", "user",
                "content", buildUserPrompt(listingFacts, photoNotes, postsPerWeek))));
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
                                "Anthropic social-calendar call failed: "
                                        + response.statusCode() + " " + errBody, 1202, 502))))
                .bodyToMono(JsonNode.class)
                .map(SocialCalendarGenerationService::parseResponse);
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
     * Parses the model's JSON answer into the calendar posts. Tolerant of code fences / surrounding prose
     * (strips to the first {@code {...}} block); a blank/non-JSON/parse failure or a missing {@code posts}
     * array yields an EMPTY list (logged) — never throws. Each post is normalized: week clamped to 1-4,
     * dayOffset clamped to 0-27, channel mapped (default INSTAGRAM), copy required (blank posts dropped).
     */
    private List<CalendarPost> parsePosts(String text) {
        List<CalendarPost> posts = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return posts;
        }
        String json = extractJsonObject(text);
        if (json == null) {
            log.debug("T10: social-calendar response was not JSON; using empty calendar");
            return posts;
        }
        try {
            JsonNode root = objectMapper.readTree(json);
            JsonNode arr = root.path("posts");
            if (!arr.isArray()) {
                log.debug("T10: social-calendar JSON had no 'posts' array; using empty calendar");
                return posts;
            }
            for (JsonNode p : arr) {
                String copy = textOrNull(p, "copy");
                if (copy == null) {
                    continue;
                }
                int week = clamp(p.path("week").asInt(1), 1, 4);
                int dayOffset = clamp(p.path("dayOffset").asInt((week - 1) * 7), 0, 27);
                MarketingChannel channel = channelOf(textOrNull(p, "channel"));
                posts.add(new CalendarPost(week, dayOffset, channel, copy));
            }
        } catch (Exception ex) {
            log.debug("T10: social-calendar JSON parse failed ({}); using empty calendar", ex.getMessage());
        }
        return posts;
    }

    private static MarketingChannel channelOf(String raw) {
        if (raw == null) {
            return MarketingChannel.INSTAGRAM;
        }
        return switch (raw.trim().toLowerCase()) {
            case "facebook", "fb" -> MarketingChannel.FACEBOOK;
            case "x", "twitter", "tweet" -> MarketingChannel.X;
            default -> MarketingChannel.INSTAGRAM;
        };
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) {
            return null;
        }
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s.trim();
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

    private static String buildUserPrompt(String listingFacts, String photoNotes, int postsPerWeek) {
        StringBuilder sb = new StringBuilder();
        sb.append("LISTING FACTS:\n").append(listingFacts == null ? "" : listingFacts);
        if (photoNotes != null && !photoNotes.isBlank()) {
            sb.append("\n\nPHOTO FEATURE NOTES (woven from the listing photos):\n").append(photoNotes);
        }
        sb.append("\n\nBuild a 4-week social calendar of about ").append(Math.max(1, postsPerWeek))
                .append(" posts per week across the channels instagram, facebook, and x. Return ONLY the JSON "
                        + "object described in the system instructions. Remember: describe the property, never "
                        + "the ideal occupant; no protected-class or steering language.");
        return sb.toString();
    }

    private BigDecimal estimateUsd(CompletionResult result) {
        boolean haiku = calendarModel != null && calendarModel.toLowerCase().contains("haiku");
        BigDecimal inPer = haiku ? HAIKU_INPUT_PER_MILLION : SONNET_INPUT_PER_MILLION;
        BigDecimal outPer = haiku ? HAIKU_OUTPUT_PER_MILLION : SONNET_OUTPUT_PER_MILLION;
        BigDecimal input = inPer.multiply(BigDecimal.valueOf(result.inputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        BigDecimal output = outPer.multiply(BigDecimal.valueOf(result.outputTokens))
                .divide(BigDecimal.valueOf(1_000_000), 6, RoundingMode.HALF_UP);
        return input.add(output);
    }

    /**
     * One parsed calendar post before date assignment + linting (the orchestrator maps {@code week}/
     * {@code dayOffset} to a real {@link java.time.LocalDate} and lints {@code copy}).
     */
    public record CalendarPost(int week, int dayOffset, MarketingChannel channel, String copy) {
    }

    private record CompletionResult(String text, long inputTokens, long outputTokens) {
    }
}
