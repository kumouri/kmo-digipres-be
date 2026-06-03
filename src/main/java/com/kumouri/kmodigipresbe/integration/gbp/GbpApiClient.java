package com.kumouri.kmodigipresbe.integration.gbp;

import com.fasterxml.jackson.databind.JsonNode;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Reads Google-Business-Profile reviews and posts replies (NMM GBP review-reply automation). Raw
 * {@link WebClient} against a <em>configurable</em> per-tenant or global base URL — NO Google SDK
 * (the codebase is uniformly raw-JSON / {@link WebClient}: {@code DocumensoClient},
 * {@code StripeCheckoutService}, {@code QuickBooksInvoiceSync}).
 *
 * <h2>No-live-Google boundary (§7 hard line)</h2>
 * The base URL is resolved as:
 * <ol>
 *   <li>{@code IntegrationConnection.config["apiBaseUrl"]} — per-tenant override</li>
 *   <li>else {@link GbpProperties#getApiBaseUrl()} — global default
 *       ({@code https://gbp.googleapis.invalid}; a non-routable placeholder)</li>
 * </ol>
 * In every test/CI run {@code kmosf.gbp.api-base-url} is overridden to the WireMock base URL via
 * {@code @DynamicPropertySource}. No code path ever hardcodes a real Google host; the OAuth
 * {@code accessToken} comes exclusively from
 * {@code IntegrationConnection.secrets["accessToken"]} (a sandbox-shaped fake in tests). The
 * {@code GbpReviewPoller} that drives this client is additionally <strong>default-OFF</strong>.
 * Wiring live Google OAuth / GBP API access is a separate, Google-approval-gated human action —
 * NOT authorized by the implementation loop.
 *
 * <h2>Adapter boundary (the F-D7 / Cal.com posture)</h2>
 * The assumed GBP wire shape lives <strong>only</strong> in this class:
 * <ul>
 *   <li>fetch: {@code GET {base}/v4/{locationName}/reviews} → {@code {reviews:[{reviewId|name,
 *       starRating, comment, reviewer:{displayName}, createTime}]}}; {@code starRating} is the GBP
 *       enum {@code STAR_RATING_UNSPECIFIED|ONE|TWO|THREE|FOUR|FIVE} mapped to 1..5 (or a plain
 *       integer, tolerated);</li>
 *   <li>post: {@code PUT {base}/v4/{reviewId}/reply} with body {@code {"comment": "<reply>"}}.</li>
 * </ul>
 * Correcting against the real Google Business Profile API (the My Business / Business Information
 * API endpoint shapes) is a change to {@link #fetchReviews} / {@link #postReply} (and the two
 * parse helpers) only — no other file assumes the wire format. The {@code locationName} path
 * segment comes from {@code IntegrationConnection.config["locationName"]} (defaults to the literal
 * {@code "reviews"}-relative path when absent, which the WireMock stub matches).
 *
 * <h2>Resilience</h2>
 * Per-call Resilience4j circuit breaker named {@code gbp-reviews} — mirrors the {@code documenso} /
 * {@code stripe-checkout} breaker wiring exactly: {@code Mono.defer} + {@code .cache()} per attempt,
 * {@code transformDeferred(CircuitBreakerOperator.of(breaker))},
 * {@code Retry.backoff(3, 1s).maxBackoff(4s)} filtering
 * {@code CallNotPermittedException} / {@code Unauthorized} / {@code DigiPresBeException},
 * {@code .timeout(requestTimeoutSeconds)}.
 *
 * <h2>Error codes</h2>
 * {@code 4030} — Google Business Profile not connected / {@code accessToken} missing (404);
 * {@code 4031} — fetch / post failed (502). {@code 2510} remains the documented cross-integration
 * not-connected fallback.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbpApiClient {

    static final String PROVIDER = "google-business";
    private static final String CB_NAME = "gbp-reviews";

    private final IntegrationConnectionRepository connections;
    private final WebClient.Builder webClientBuilder;
    private final GbpProperties properties;
    private final CircuitBreakerRegistry breakers;

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Fetches the location's reviews for the tenant in the active reactive context.
     *
     * @return the normalized reviews; errors with {@code DigiPresBeException(4030, 404)} if the
     *         tenant has no {@code google-business} connection / no accessToken,
     *         {@code DigiPresBeException(4031, 502)} on a fetch failure
     */
    public Mono<List<GbpReview>> fetchReviews() {
        return TenantContextHolder.required().flatMap(ctx ->
                resolveConnection(ctx.tenantId()).flatMap(conn -> {
                    String accessToken = accessTokenOrNull(conn);
                    if (accessToken == null) {
                        return Mono.error(notConnected());
                    }
                    String baseUrl = resolveBaseUrl(conn);
                    String locationPath = resolveLocationPath(conn);
                    return callFetch(baseUrl, accessToken, locationPath);
                }));
    }

    /**
     * Posts a reply to a single review for the tenant in the active reactive context.
     *
     * @param reviewId  the GBP review id
     * @param replyText the reply body
     * @return completes empty on success; errors {@code 4030}/{@code 4031} as
     *         {@link #fetchReviews}
     */
    public Mono<Void> postReply(String reviewId, String replyText) {
        return TenantContextHolder.required().flatMap(ctx ->
                resolveConnection(ctx.tenantId()).flatMap(conn -> {
                    String accessToken = accessTokenOrNull(conn);
                    if (accessToken == null) {
                        return Mono.<Void>error(notConnected());
                    }
                    String baseUrl = resolveBaseUrl(conn);
                    return callPostReply(baseUrl, accessToken, reviewId, replyText);
                }));
    }

    // -------------------------------------------------------------------------
    // Connection / base-url resolution
    // -------------------------------------------------------------------------

    private Mono<IntegrationConnection> resolveConnection(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .switchIfEmpty(Mono.error(this::notConnected));
    }

    private static String accessTokenOrNull(IntegrationConnection conn) {
        if (conn.getSecrets() == null) return null;
        String token = conn.getSecrets().get("accessToken");
        return (token == null || token.isBlank()) ? null : token;
    }

    private DigiPresBeException notConnected() {
        return new DigiPresBeException(
                "Google Business Profile is not connected for this tenant", 4030, 404);
    }

    private String resolveBaseUrl(IntegrationConnection conn) {
        // Base-URL precedence: IntegrationConnection.config["apiBaseUrl"] -> global default.
        if (conn.getConfig() != null) {
            String perTenant = conn.getConfig().get("apiBaseUrl");
            if (perTenant != null && !perTenant.isBlank()) {
                return perTenant;
            }
        }
        return properties.getApiBaseUrl();
    }

    /**
     * The GBP location resource the reviews are read from
     * ({@code accounts/{a}/locations/{l}}), from {@code config["locationName"]}. When absent the
     * fetch falls back to a literal {@code "reviews"} relative path (matched by the WireMock stub).
     */
    private static String resolveLocationPath(IntegrationConnection conn) {
        if (conn.getConfig() != null) {
            String loc = conn.getConfig().get("locationName");
            if (loc != null && !loc.isBlank()) {
                return loc;
            }
        }
        return "";
    }

    // -------------------------------------------------------------------------
    // Calls (the adapter boundary — assumed GBP wire shape isolated here)
    // -------------------------------------------------------------------------

    private Mono<List<GbpReview>> callFetch(String baseUrl, String accessToken, String locationPath) {
        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        String uri = locationPath.isEmpty()
                ? "/v4/reviews"
                : "/v4/" + locationPath + "/reviews";

        Mono<List<GbpReview>> attempt = Mono.defer(() ->
                client.get()
                        .uri(uri)
                        .header("Authorization", "Bearer " + accessToken)
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .cache()
                        .map(GbpApiClient::parseReviews));

        return attempt
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(retrySpec())
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "GBP fetch-reviews failed: " + err.getMessage(), 4031, 502);
                });
    }

    private Mono<Void> callPostReply(String baseUrl, String accessToken, String reviewId,
                                     String replyText) {
        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        Map<String, Object> body = Map.of("comment", replyText == null ? "" : replyText);

        Mono<Void> attempt = Mono.defer(() ->
                client.put()
                        .uri("/v4/{reviewId}/reply", reviewId)
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .cache()
                        .then());

        return attempt
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(retrySpec())
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "GBP post-reply failed: " + err.getMessage(), 4031, 502);
                });
    }

    private static Retry retrySpec() {
        return Retry.backoff(3, Duration.ofSeconds(1))
                .maxBackoff(Duration.ofSeconds(4))
                .filter(t ->
                        !(t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException)
                        && !(t instanceof WebClientResponseException.Unauthorized)
                        && !(t instanceof DigiPresBeException));
    }

    // -------------------------------------------------------------------------
    // GBP JSON → GbpReview (the ONLY place the review wire shape is assumed)
    // -------------------------------------------------------------------------

    private static List<GbpReview> parseReviews(JsonNode root) {
        List<GbpReview> result = new ArrayList<>();
        JsonNode reviews = root.path("reviews");
        if (reviews.isArray()) {
            for (JsonNode r : reviews) {
                String reviewId = r.path("reviewId").asText(null);
                if (reviewId == null || reviewId.isBlank()) {
                    reviewId = r.path("name").asText(null);
                }
                if (reviewId == null || reviewId.isBlank()) {
                    // A review with no id cannot be deduped — skip it (defensive).
                    continue;
                }
                Integer rating = parseStarRating(r.path("starRating"));
                String comment = textOrNull(r, "comment");
                String reviewerName = r.path("reviewer").path("displayName").asText(null);
                if (reviewerName != null && reviewerName.isBlank()) reviewerName = null;
                Instant createTime = parseInstant(r.path("createTime").asText(null));
                result.add(new GbpReview(reviewId, rating, comment, reviewerName, createTime));
            }
        }
        return result;
    }

    /**
     * Maps the GBP {@code starRating} (the enum {@code ONE..FIVE}) to 1..5; tolerates a plain
     * integer too. {@code STAR_RATING_UNSPECIFIED} / unrecognized → {@code null}.
     */
    private static Integer parseStarRating(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return null;
        if (node.isInt()) {
            int v = node.asInt();
            return (v >= 1 && v <= 5) ? v : null;
        }
        String s = node.asText("");
        return switch (s) {
            case "ONE" -> 1;
            case "TWO" -> 2;
            case "THREE" -> 3;
            case "FOUR" -> 4;
            case "FIVE" -> 5;
            default -> null;
        };
    }

    private static Instant parseInstant(String s) {
        if (s == null || s.isBlank()) return null;
        try {
            return Instant.parse(s);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
