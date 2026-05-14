package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * OAuth2 dance for QuickBooks Online. Three responsibilities:
 *
 * <ol>
 *   <li>{@link #buildAuthorizationUrl} — produce the Intuit consent URL with a
 *       signed {@code state} parameter encoding the initiating tenant id, so
 *       the callback can prove which tenant authorised the connection without
 *       trusting URL-tampering.</li>
 *   <li>{@link #exchangeAuthCode} — verify the signed state, POST the
 *       authorisation code to Intuit's {@code /oauth2/v1/tokens/bearer}
 *       endpoint, and persist the resulting access/refresh tokens (plus the
 *       Intuit-supplied {@code realmId}) on an
 *       {@link IntegrationConnection} for the tenant.</li>
 *   <li>{@link #refreshIfNeeded} — if a connection's access token is within
 *       the {@code refreshSkewSeconds} window of expiry, exchange the refresh
 *       token for a new pair and persist the rotation.</li>
 * </ol>
 *
 * <p>HTTP is non-blocking via {@link WebClient}; no explicit
 * {@code Schedulers.boundedElastic()} wrap is needed (WebClient is reactor-native).
 *
 * <p>Error codes: {@code 2800} (no connection), {@code 2801} (token refresh
 * failed), {@code 2810} (state missing/expired/malformed — used by the callback),
 * {@code 2812} (state signature invalid).
 */
@Slf4j
@Service
public class QuickBooksOAuthService {

    public static final String PROVIDER = "quickbooks";
    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder URL_ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder URL_DEC = Base64.getUrlDecoder();
    /** OAuth2 scope Intuit grants to access invoice and customer endpoints. */
    private static final String SCOPE = "com.intuit.quickbooks.accounting";

    private final QuickBooksProperties props;
    private final IntegrationConnectionRepository connections;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final byte[] stateSecret;
    private final SecureRandom random = new SecureRandom();

    public QuickBooksOAuthService(QuickBooksProperties props,
                                  IntegrationConnectionRepository connections,
                                  WebClient.Builder webClientBuilder,
                                  ObjectMapper objectMapper) {
        this.props = props;
        this.connections = connections;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        String secret = props.getStateSigningSecret();
        if (secret == null || secret.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.stateSecret = generated;
            log.warn("kmosf.integrations.quickbooks.state-signing-secret is unset; "
                    + "using a fresh random secret. OAuth state tokens invalidate on restart. "
                    + "Set the env var for production.");
        } else {
            this.stateSecret = secret.getBytes(StandardCharsets.UTF_8);
        }
    }

    /**
     * Build the Intuit OAuth2 consent URL. The {@code state} parameter is a
     * signed token binding the initiating tenant id + an expiry timestamp so
     * Intuit's redirect can be tied back to the tenant that started the flow.
     */
    public Mono<String> buildAuthorizationUrl(UUID tenantId) {
        if (tenantId == null) {
            return Mono.error(new DigiPresBeException(
                    "tenantId required to start QBO consent flow", 2810, 400));
        }
        long exp = Instant.now().getEpochSecond() + props.getStateTtlSeconds();
        String state = signState(tenantId, exp);
        String url = props.getAuthorizationBaseUrl() + "/connect/oauth2"
                + "?client_id=" + enc(props.getClientId())
                + "&response_type=code"
                + "&scope=" + enc(SCOPE)
                + "&redirect_uri=" + enc(props.getRedirectUri())
                + "&state=" + enc(state);
        return Mono.just(url);
    }

    /**
     * Verify the signed state, exchange the authorisation code for tokens, and
     * persist (upsert) the resulting {@link IntegrationConnection}.
     */
    public Mono<IntegrationConnection> exchangeAuthCode(String code, String state, String realmId) {
        if (code == null || code.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "OAuth code missing on callback", 2810, 400));
        }
        if (realmId == null || realmId.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "OAuth realmId missing on callback", 2810, 400));
        }
        // verifyState can throw DigiPresBeException synchronously — wrap in
        // Mono.fromCallable so the exception propagates as a Mono.error and the
        // GlobalErrorHandler translates it cleanly.
        return Mono.fromCallable(() -> verifyState(state))
                .flatMap(tenantId -> {
                    MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
                    form.add("grant_type", "authorization_code");
                    form.add("code", code);
                    form.add("redirect_uri", props.getRedirectUri());
                    return postTokenForm(form)
                            .flatMap(tokenResp -> persistConnection(tenantId, realmId, tokenResp));
                })
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "QBO auth code exchange failed: " + err.getMessage(), 2801, 502);
                });
    }

    /**
     * If {@code conn.tokenExpiresAt} is within {@link QuickBooksProperties#getRefreshSkewSeconds()}
     * of now, POST the refresh-token grant and rotate. Otherwise return the
     * connection untouched.
     */
    public Mono<IntegrationConnection> refreshIfNeeded(IntegrationConnection conn) {
        if (conn == null) {
            return Mono.error(new DigiPresBeException(
                    "No QBO connection to refresh", 2800, 412));
        }
        Map<String, String> secrets = conn.getSecrets() == null ? Map.of() : conn.getSecrets();
        String expiresStr = secrets.get("tokenExpiresAt");
        String refreshToken = secrets.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "QBO connection missing refreshToken", 2801, 412));
        }
        Instant expiresAt = parseInstant(expiresStr);
        Instant threshold = Instant.now().plusSeconds(props.getRefreshSkewSeconds());
        if (expiresAt != null && expiresAt.isAfter(threshold)) {
            return Mono.just(conn);
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);

        return postTokenForm(form)
                .flatMap(tokenResp -> {
                    Map<String, String> newSecrets = new HashMap<>(secrets);
                    String access = tokenResp.path("access_token").asText(null);
                    String newRefresh = tokenResp.path("refresh_token").asText(refreshToken);
                    long expiresIn = tokenResp.path("expires_in").asLong(3600L);
                    if (access == null) {
                        return Mono.error(new DigiPresBeException(
                                "QBO refresh response missing access_token", 2801, 502));
                    }
                    newSecrets.put("accessToken", access);
                    newSecrets.put("refreshToken", newRefresh);
                    newSecrets.put("tokenExpiresAt",
                            Instant.now().plusSeconds(expiresIn).toString());
                    conn.setSecrets(newSecrets);
                    return saveAsTenant(conn);
                })
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "QBO token refresh failed: " + err.getMessage(), 2801, 502);
                });
    }

    private Mono<JsonNode> postTokenForm(MultiValueMap<String, String> form) {
        String basic = "Basic " + Base64.getEncoder().encodeToString(
                (props.getClientId() + ":" + props.getClientSecret())
                        .getBytes(StandardCharsets.UTF_8));
        WebClient client = webClientBuilder.baseUrl(props.getOauthBaseUrl()).build();
        return client.post()
                .uri("/oauth2/v1/tokens/bearer")
                .header("Authorization", basic)
                .header("Accept", "application/json")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(raw -> {
                    try {
                        return Mono.just(objectMapper.readTree(raw));
                    } catch (Exception ex) {
                        return Mono.error(new DigiPresBeException(
                                "QBO token endpoint returned non-JSON: " + ex.getMessage(),
                                2801, 502));
                    }
                });
    }

    private Mono<IntegrationConnection> persistConnection(UUID tenantId, String realmId, JsonNode tokenResp) {
        String access = tokenResp.path("access_token").asText(null);
        String refresh = tokenResp.path("refresh_token").asText(null);
        long expiresIn = tokenResp.path("expires_in").asLong(3600L);
        if (access == null || refresh == null) {
            return Mono.error(new DigiPresBeException(
                    "QBO token response missing access_token or refresh_token", 2801, 502));
        }
        Map<String, String> secrets = new HashMap<>();
        secrets.put("accessToken", access);
        secrets.put("refreshToken", refresh);
        secrets.put("tokenExpiresAt", Instant.now().plusSeconds(expiresIn).toString());
        // Per-tenant webhook verifier token — Intuit posts notifications signed with
        // this secret (HMAC-SHA256 of the body). Generated here at connect-time.
        byte[] verifier = new byte[32];
        random.nextBytes(verifier);
        secrets.put("webhookVerifierToken", URL_ENC.encodeToString(verifier));

        Map<String, String> config = new HashMap<>();
        config.put("realmId", realmId);

        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .flatMap(existing -> {
                    existing.setSecrets(secrets);
                    existing.setConfig(config);
                    existing.setStatus(IntegrationConnection.Status.ACTIVE);
                    existing.setConnectedAt(Instant.now());
                    return saveAsTenant(existing);
                })
                .switchIfEmpty(Mono.defer(() -> {
                    IntegrationConnection fresh = IntegrationConnection.builder()
                            .tenantId(tenantId)
                            .provider(PROVIDER)
                            .displayName("QuickBooks Online")
                            .status(IntegrationConnection.Status.ACTIVE)
                            .secrets(secrets)
                            .config(config)
                            .connectedAt(Instant.now())
                            .build();
                    return saveAsTenant(fresh);
                }));
    }

    private Mono<IntegrationConnection> saveAsTenant(IntegrationConnection conn) {
        TenantContext synthetic = new TenantContext(
                conn.getTenantId(), null, Set.of("INTEGRATION_QUICKBOOKS"));
        return connections.save(conn)
                .contextWrite(TenantContextHolder.write(synthetic));
    }

    // ----- state signing / verification ------------------------------------

    String signState(UUID tenantId, long expiresAtEpochSeconds) {
        String payload = tenantId + "|" + expiresAtEpochSeconds;
        byte[] sig = hmac(payload.getBytes(StandardCharsets.UTF_8));
        return URL_ENC.encodeToString(payload.getBytes(StandardCharsets.UTF_8))
                + "." + URL_ENC.encodeToString(sig);
    }

    UUID verifyState(String state) {
        if (state == null || state.isBlank()) {
            throw new DigiPresBeException("OAuth state missing", 2810, 400);
        }
        int dot = state.indexOf('.');
        if (dot <= 0 || dot == state.length() - 1) {
            throw new DigiPresBeException("OAuth state malformed", 2810, 400);
        }
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            payloadBytes = URL_DEC.decode(state.substring(0, dot));
            providedSig = URL_DEC.decode(state.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("OAuth state not base64url", 2810, 400);
        }
        byte[] expectedSig = hmac(payloadBytes);
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new DigiPresBeException("OAuth state signature invalid", 2812, 401);
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|");
        if (parts.length != 2) {
            throw new DigiPresBeException("OAuth state payload malformed", 2810, 400);
        }
        UUID tenantId;
        long exp;
        try {
            tenantId = UUID.fromString(parts[0]);
            exp = Long.parseLong(parts[1]);
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("OAuth state payload malformed", 2810, 400);
        }
        if (Instant.ofEpochSecond(exp).isBefore(Instant.now())) {
            throw new DigiPresBeException("OAuth state expired", 2810, 400);
        }
        return tenantId;
    }

    private byte[] hmac(byte[] payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(stateSecret, HMAC_ALG));
            return mac.doFinal(payload);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable on this JVM", ex);
        }
    }

    private static String enc(String s) {
        return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8);
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try {
            return Instant.parse(iso);
        } catch (RuntimeException ex) {
            return null;
        }
    }
}
