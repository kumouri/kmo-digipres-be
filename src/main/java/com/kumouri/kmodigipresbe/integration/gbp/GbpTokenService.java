package com.kumouri.kmodigipresbe.integration.gbp;

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

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Refreshes an expired Google-Business-Profile OAuth2 access token (NMM GBP review-reply
 * automation). Google access tokens expire (~1h); a long-running default-OFF {@code GbpReviewPoller}
 * therefore eventually authenticates a fetch/post with a token Google has aged out, and the GBP API
 * answers <strong>401</strong>. {@link GbpApiClient} catches that 401 and calls
 * {@link #refreshAccessToken(IntegrationConnection)} to mint a fresh token, then retries the
 * original call once.
 *
 * <h2>A faithful mirror of {@code QuickBooksOAuthService} (the closest in-repo OAuth-refresh
 * precedent)</h2>
 * The token-exchange POST shape is the {@code QuickBooksOAuthService.postTokenForm} mirror — a
 * {@code application/x-www-form-urlencoded} body via {@link BodyInserters#fromFormData} carrying
 * {@code grant_type=refresh_token} + the per-tenant {@code refreshToken} + the OAuth-app
 * {@code client_id}/{@code client_secret}. The rotated token is persisted under a synthetic
 * {@code TenantContext(tenantId, null, Set.of("INTEGRATION_GBP"))} — the
 * {@code QuickBooksOAuthService.saveAsTenant} pattern (a poll cycle runs under the
 * {@code AUTOMATION_GBP_REVIEWS} context, but the save is made self-sufficient here so the
 * refresh is reusable from any caller).
 *
 * <h2>Credential split (documented in {@link GbpProperties})</h2>
 * <ul>
 *   <li><strong>Per-tenant</strong> — {@code refreshToken} (and the rotated {@code accessToken} /
 *       {@code tokenExpiresAt}) in {@code IntegrationConnection(google-business).secrets}.</li>
 *   <li><strong>Global</strong> — the OAuth app's {@code clientId}/{@code clientSecret} +
 *       {@code tokenUrl} on {@code kmosf.gbp.*} ({@link GbpProperties}); the single KMOSF
 *       Google-Cloud OAuth client serves every tenant (the {@code QuickBooksProperties} posture).</li>
 * </ul>
 *
 * <h2>§7 no-live-Google + §9 reactive</h2>
 * {@code tokenUrl} defaults to a non-routable {@code .invalid} host and is overridden to WireMock in
 * every test; {@code clientSecret}/{@code refreshToken} are sandbox fakes — no host hardcoded, no
 * live OAuth in the implementation loop. HTTP is the reactor-native {@link WebClient} (no blocking
 * call on the Netty loop); the only {@code switchIfEmpty}-free control flow is explicit error
 * branches (never {@code switchIfEmpty(create)}).
 *
 * <h2>Errors</h2>
 * A missing {@code refreshToken}, a non-2xx token response, or a token response without an
 * {@code access_token} surfaces {@code DigiPresBeException(4034, 502)} (gbp-token-refresh-failed) —
 * the GBP-block code {@code GbpApiClient} re-raises after a failed refresh.
 */
@Slf4j
@Service
public class GbpTokenService {

    /** gbp-token-refresh-failed (GBP block 4030-4049; first free in the reserved 4034-4049 range). */
    static final int ERR_REFRESH_FAILED = 4034;

    private final IntegrationConnectionRepository connections;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final GbpProperties properties;

    public GbpTokenService(IntegrationConnectionRepository connections,
                           WebClient.Builder webClientBuilder,
                           ObjectMapper objectMapper,
                           GbpProperties properties) {
        this.connections = connections;
        this.webClientBuilder = webClientBuilder;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * Exchanges the connection's stored {@code refreshToken} for a fresh access token, persists the
     * rotation onto {@code IntegrationConnection(google-business).secrets}, and emits the new access
     * token (so the caller can retry the original request).
     *
     * @param conn the tenant's {@code google-business} connection (must carry a {@code refreshToken})
     * @return the new access token; errors {@code DigiPresBeException(4034, 502)} on any failure
     */
    public Mono<String> refreshAccessToken(IntegrationConnection conn) {
        if (conn == null) {
            return Mono.error(new DigiPresBeException(
                    "No google-business connection to refresh", ERR_REFRESH_FAILED, 502));
        }
        Map<String, String> secrets = conn.getSecrets() == null ? Map.of() : conn.getSecrets();
        String refreshToken = secrets.get("refreshToken");
        if (refreshToken == null || refreshToken.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "google-business connection has no refreshToken; cannot refresh the expired "
                            + "access token (a re-consent is required)", ERR_REFRESH_FAILED, 502));
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", refreshToken);
        form.add("client_id", properties.getClientId());
        form.add("client_secret", properties.getClientSecret());

        return postTokenForm(form)
                .flatMap(tokenResp -> {
                    String access = tokenResp.path("access_token").asText(null);
                    if (access == null || access.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "google-business token refresh response missing access_token",
                                ERR_REFRESH_FAILED, 502));
                    }
                    // Google may (re-)issue a refresh_token; keep the old one if it doesn't.
                    String newRefresh = tokenResp.path("refresh_token").asText(refreshToken);
                    long expiresIn = tokenResp.path("expires_in").asLong(3600L);

                    Map<String, String> newSecrets = new HashMap<>(secrets);
                    newSecrets.put("accessToken", access);
                    newSecrets.put("refreshToken", newRefresh);
                    newSecrets.put("tokenExpiresAt", Instant.now().plusSeconds(expiresIn).toString());
                    conn.setSecrets(newSecrets);
                    return saveAsTenant(conn).thenReturn(access);
                })
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "google-business token refresh failed: " + err.getMessage(),
                            ERR_REFRESH_FAILED, 502);
                });
    }

    private Mono<JsonNode> postTokenForm(MultiValueMap<String, String> form) {
        WebClient client = webClientBuilder.baseUrl(properties.getTokenUrl()).build();
        return client.post()
                .uri("")
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(BodyInserters.fromFormData(form))
                .retrieve()
                .bodyToMono(String.class)
                .flatMap(raw -> {
                    try {
                        return Mono.just(objectMapper.readTree(raw));
                    } catch (Exception ex) {
                        return Mono.error(new DigiPresBeException(
                                "google-business token endpoint returned non-JSON: "
                                        + ex.getMessage(), ERR_REFRESH_FAILED, 502));
                    }
                });
    }

    /**
     * Persists the rotated connection under a synthetic {@code INTEGRATION_GBP} tenant context so
     * the {@code TenantScoped} repository save resolves a tenant (the
     * {@code QuickBooksOAuthService.saveAsTenant} pattern).
     */
    private Mono<IntegrationConnection> saveAsTenant(IntegrationConnection conn) {
        TenantContext synthetic = new TenantContext(
                conn.getTenantId(), null, Set.of("INTEGRATION_GBP"));
        return connections.save(conn)
                .contextWrite(TenantContextHolder.write(synthetic));
    }
}
