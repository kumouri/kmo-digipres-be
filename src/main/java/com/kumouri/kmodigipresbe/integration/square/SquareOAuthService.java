package com.kumouri.kmodigipresbe.integration.square;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

/**
 * Manages the Square OAuth2 authorization-code flow. The state parameter
 * is HMAC-SHA256 signed and carries the tenant id + expiry, so the callback
 * can establish tenant identity without a server-side session.
 *
 * <p>Token storage: access token, refresh token, and expiry are stored in
 * {@link IntegrationConnection#getSecrets()} (keys {@code accessToken},
 * {@code refreshToken}, {@code expiresAt}); the Square merchant id is in
 * {@link IntegrationConnection#getConfig()} (key {@code merchantId}).
 */
@Slf4j
@RequiredArgsConstructor
public class SquareOAuthService {

    static final String PROVIDER = "square";

    private final SquareProperties props;
    private final IntegrationConnectionRepository connections;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;

    public Mono<String> buildAuthorizationUrl(UUID tenantId) {
        return Mono.fromCallable(() -> {
            String state = buildSignedState(tenantId);
            return props.getApiBaseUrl() + "/oauth2/authorize"
                    + "?client_id=" + props.getClientId()
                    + "&scope=PAYMENTS_READ+PAYMENTS_WRITE+MERCHANT_PROFILE_READ"
                    + "&session=false"
                    + "&state=" + state
                    + "&redirect_uri=" + props.getRedirectUri();
        }).subscribeOn(Schedulers.boundedElastic());
    }

    public Mono<IntegrationConnection> exchangeAuthCode(String code, String state) {
        return Mono.fromCallable(() -> extractTenantFromState(state))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(tenantId -> callTokenEndpoint(Map.of(
                        "client_id", props.getClientId(),
                        "client_secret", props.getClientSecret(),
                        "code", code,
                        "grant_type", "authorization_code",
                        "redirect_uri", props.getRedirectUri()))
                        .flatMap(json -> saveConnection(tenantId, json)));
    }

    public Mono<IntegrationConnection> refreshIfNeeded(IntegrationConnection conn) {
        String expiresAtStr = conn.getSecrets().get("expiresAt");
        if (expiresAtStr == null) return Mono.just(conn);
        Instant expiresAt = Instant.parse(expiresAtStr);
        if (Instant.now().plusSeconds(props.getRefreshSkewSeconds()).isBefore(expiresAt)) {
            return Mono.just(conn);
        }
        String refreshToken = conn.getSecrets().get("refreshToken");
        if (refreshToken == null) return Mono.just(conn);
        return callTokenEndpoint(Map.of(
                "client_id", props.getClientId(),
                "client_secret", props.getClientSecret(),
                "refresh_token", refreshToken,
                "grant_type", "refresh_token"))
                .flatMap(json -> saveConnection(conn.getTenantId(), json));
    }

    private Mono<JsonNode> callTokenEndpoint(Map<String, String> body) {
        WebClient client = webClientBuilder.baseUrl(props.getApiBaseUrl()).build();
        return client.post()
                .uri("/oauth2/token")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(raw -> Mono.fromCallable(() -> objectMapper.readTree(raw))
                        .subscribeOn(Schedulers.boundedElastic()))
                .doOnError(e -> log.error("Square token endpoint error", e));
    }

    private Mono<IntegrationConnection> saveConnection(UUID tenantId, JsonNode json) {
        String accessToken = json.path("access_token").asText();
        String refreshToken = json.path("refresh_token").asText(null);
        String expiresAt = json.path("expires_at").asText(null);
        String merchantId = json.path("merchant_id").asText();

        if (accessToken.isBlank() || merchantId.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Square token response missing access_token or merchant_id", 3000, 502));
        }

        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .defaultIfEmpty(IntegrationConnection.builder()
                        .tenantId(tenantId)
                        .provider(PROVIDER)
                        .displayName("Square POS")
                        .connectedAt(Instant.now())
                        .build())
                .flatMap(conn -> {
                    var secrets = new java.util.HashMap<>(conn.getSecrets());
                    secrets.put("accessToken", accessToken);
                    if (refreshToken != null) secrets.put("refreshToken", refreshToken);
                    if (expiresAt != null) secrets.put("expiresAt", expiresAt);
                    var config = new java.util.HashMap<>(conn.getConfig());
                    config.put("merchantId", merchantId);
                    return connections.save(conn.toBuilder()
                            .secrets(secrets)
                            .config(config)
                            .status(IntegrationConnection.Status.ACTIVE)
                            .lastUsedAt(Instant.now())
                            .build());
                });
    }

    private String buildSignedState(UUID tenantId) {
        long expiry = System.currentTimeMillis() / 1000L + props.getStateTtlSeconds();
        String payload = tenantId + ":" + expiry;
        String sig = hmacHex(signingKey(), payload);
        return payload + ":" + sig;
    }

    private UUID extractTenantFromState(String state) {
        if (state == null) throw new DigiPresBeException("Missing state parameter", 3001, 400);
        String[] parts = state.split(":", 3);
        if (parts.length != 3) throw new DigiPresBeException("Malformed state parameter", 3001, 400);
        long expiry;
        try {
            expiry = Long.parseLong(parts[1]);
        } catch (NumberFormatException e) {
            throw new DigiPresBeException("Malformed state parameter", 3001, 400);
        }
        if (System.currentTimeMillis() / 1000L > expiry) {
            throw new DigiPresBeException("State parameter expired", 3001, 400);
        }
        String payload = parts[0] + ":" + parts[1];
        String expected = hmacHex(signingKey(), payload);
        if (!constantTimeEquals(expected, parts[2])) {
            throw new DigiPresBeException("State parameter signature invalid", 3001, 401);
        }
        try {
            return UUID.fromString(parts[0]);
        } catch (IllegalArgumentException e) {
            throw new DigiPresBeException("Malformed tenant id in state", 3001, 400);
        }
    }

    private String signingKey() {
        String s = props.getStateSigningSecret();
        if (s == null || s.isBlank()) {
            s = "dev-square-state-key";
        }
        return s;
    }

    private static String hmacHex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC failed", ex);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
