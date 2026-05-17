package com.kumouri.kmodigipresbe.integration.documenso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.contract.Contract;
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
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sends contracts to Documenso for e-signature and downloads the signed PDF
 * (Phase F — F-D5). Raw {@link WebClient} against a <em>configurable</em>
 * per-tenant or global base URL — NO Documenso SDK (the codebase is uniformly
 * raw-JSON / {@link WebClient}: {@code StripeCheckoutService},
 * {@code QuickBooksInvoiceSync}).
 *
 * <h2>No-live-Documenso boundary (§7 hard line)</h2>
 * The base URL is resolved as:
 * <ol>
 *   <li>{@code IntegrationConnection.config["apiBaseUrl"]} — per-tenant Documenso
 *       deploy (the ultraplan's "per-tenant Documenso deploy")</li>
 *   <li>else {@link DocumensoProperties#getApiBaseUrl()} — global default
 *       ({@code https://documenso.internal.invalid}; a non-routable placeholder)</li>
 * </ol>
 * In every test/CI run {@code kmosf.documenso.api-base-url} is overridden to the
 * WireMock base URL via {@code @DynamicPropertySource}. No code path ever
 * hardcodes a real Documenso host; {@code apiToken} comes exclusively from
 * {@code IntegrationConnection.secrets["apiToken"]} (a sandbox-shaped fake in
 * tests). Wiring a live Documenso deployment is a separate human action — NOT
 * authorized by the implementation loop.
 *
 * <h2>Resilience</h2>
 * Per-call Resilience4j circuit breaker named {@code documenso} — mirrors the
 * {@code stripe-checkout} breaker wiring exactly:
 * {@code Mono.defer} + {@code .cache()} per attempt,
 * {@code transformDeferred(CircuitBreakerOperator.of(breaker))},
 * {@code Retry.backoff(3, 1s).maxBackoff(4s)} filtering
 * {@code CallNotPermittedException} / {@code Unauthorized} / {@code DigiPresBeException},
 * {@code .timeout(requestTimeoutSeconds)}.
 *
 * <h2>Error codes</h2>
 * {@code 3720} — {@code apiToken} missing / not configured (412);
 * {@code 3721} — send / download failed (502). Reused: {@code 3707} Contract not
 * found (from {@code ContractService}); {@code 2510} Documenso not connected.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DocumensoClient {

    static final String PROVIDER = "documenso";
    private static final String CB_NAME = "documenso";

    private final IntegrationConnectionRepository connections;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final DocumensoProperties properties;
    private final CircuitBreakerRegistry breakers;

    /**
     * Result of a successful {@link #sendForSignature} call.
     *
     * @param documensoDocumentId the document id assigned by Documenso — used as
     *                            the webhook correlation key (F-D7)
     */
    public record DocumensoSendResult(String documensoDocumentId) {}

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Uploads the rendered PDF and creates a Documenso document for the given
     * recipient, returning the Documenso document id.
     *
     * <p>The contract's tenant is resolved from the reactive context
     * ({@link TenantContextHolder#required()}) so this method must be called
     * within a tenant-scoped Mono chain.
     *
     * @param contract       the {@link Contract} being sent (used for title / metadata)
     * @param renderedPdf    PDF bytes of the pre-signature rendered contract
     * @param recipientEmail recipient e-mail for the signature request
     * @param recipientName  recipient display name
     * @return {@link DocumensoSendResult} on success; errors with
     *         {@code DigiPresBeException(3720, 412)} if no apiToken,
     *         {@code DigiPresBeException(3721, 502)} on send failure
     */
    public Mono<DocumensoSendResult> sendForSignature(
            Contract contract,
            byte[] renderedPdf,
            String recipientEmail,
            String recipientName) {

        return TenantContextHolder.required().flatMap(ctx ->
                resolveConnection(ctx.tenantId()).flatMap(conn -> {
                    String apiToken = conn.getSecrets() == null
                            ? null : conn.getSecrets().get("apiToken");
                    if (apiToken == null || apiToken.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Documenso apiToken is not configured for this tenant",
                                3720, 412));
                    }
                    String baseUrl = resolveBaseUrl(conn);
                    return callSend(baseUrl, apiToken, contract, renderedPdf,
                            recipientEmail, recipientName);
                }));
    }

    /**
     * Downloads the signed PDF from Documenso by document id (fallback when no
     * download URL is included in the webhook payload — F-D7 adapter).
     *
     * @param documensoDocumentId the Documenso document id (used to build the
     *                            download path)
     * @return raw PDF bytes; errors with {@code DigiPresBeException(3721, 502)} on
     *         failure
     */
    public Mono<byte[]> downloadSignedPdf(String documensoDocumentId) {
        return TenantContextHolder.required().flatMap(ctx ->
                resolveConnection(ctx.tenantId()).flatMap(conn -> {
                    String apiToken = conn.getSecrets() == null
                            ? null : conn.getSecrets().get("apiToken");
                    if (apiToken == null || apiToken.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Documenso apiToken is not configured for this tenant",
                                3720, 412));
                    }
                    String baseUrl = resolveBaseUrl(conn);
                    return callDownload(baseUrl, apiToken, documensoDocumentId);
                }));
    }

    /**
     * Downloads the signed PDF from a direct URL (primary path when the webhook
     * payload includes a {@code downloadUrl} — F-D7 adapter). The {@code apiToken}
     * is still required as a Bearer credential.
     *
     * @param downloadUrl absolute URL of the signed PDF served by Documenso
     * @return raw PDF bytes; errors with {@code DigiPresBeException(3721, 502)} on
     *         failure
     */
    public Mono<byte[]> downloadSignedPdfFromUrl(String downloadUrl) {
        return TenantContextHolder.required().flatMap(ctx ->
                resolveConnection(ctx.tenantId()).flatMap(conn -> {
                    String apiToken = conn.getSecrets() == null
                            ? null : conn.getSecrets().get("apiToken");
                    if (apiToken == null || apiToken.isBlank()) {
                        return Mono.error(new DigiPresBeException(
                                "Documenso apiToken is not configured for this tenant",
                                3720, 412));
                    }
                    return callDownloadUrl(apiToken, downloadUrl);
                }));
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Mono<com.kumouri.kmodigipresbe.integration.IntegrationConnection>
            resolveConnection(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Documenso is not connected for this tenant", 2510, 404)));
    }

    private String resolveBaseUrl(
            com.kumouri.kmodigipresbe.integration.IntegrationConnection conn) {
        // Base-URL precedence: IntegrationConnection.config["apiBaseUrl"] → global default
        if (conn.getConfig() != null) {
            String perTenant = conn.getConfig().get("apiBaseUrl");
            if (perTenant != null && !perTenant.isBlank()) {
                return perTenant;
            }
        }
        return properties.getApiBaseUrl();
    }

    /**
     * Calls the Documenso "create document + send" API endpoint.
     *
     * <p>The assumed request shape (F-D5 / F-D7 — encapsulated here; correcting
     * against a real Documenso deployment is a change to this method only):
     * {@code POST /api/v1/documents} with a JSON body carrying the PDF (base64),
     * title, and recipient list. The response must contain a {@code documentId}
     * field.
     */
    private Mono<DocumensoSendResult> callSend(
            String baseUrl, String apiToken, Contract contract,
            byte[] renderedPdf, String recipientEmail, String recipientName) {

        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();

        Map<String, Object> body = new HashMap<>();
        body.put("title", contract.getTitle());
        body.put("documentContent", Base64.getEncoder().encodeToString(renderedPdf));
        Map<String, String> recipient = new HashMap<>();
        recipient.put("email", recipientEmail != null ? recipientEmail : "");
        recipient.put("name", recipientName != null ? recipientName : "");
        body.put("recipients", List.of(recipient));

        Mono<DocumensoSendResult> attempt = Mono.defer(() ->
                client.post()
                        .uri("/api/v1/documents")
                        .header("Authorization", "Bearer " + apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(String.class)
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .cache()
                        .flatMap(this::extractDocumentId));

        return attempt
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .filter(t ->
                                !(t instanceof io.github.resilience4j.circuitbreaker
                                        .CallNotPermittedException)
                                && !(t instanceof WebClientResponseException.Unauthorized)
                                && !(t instanceof DigiPresBeException)))
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "Documenso send failed: " + err.getMessage(), 3721, 502);
                });
    }

    /**
     * Downloads the signed PDF via {@code GET /api/v1/documents/{id}/download}.
     * Used when the webhook payload does not include a {@code downloadUrl}.
     */
    private Mono<byte[]> callDownload(
            String baseUrl, String apiToken, String documensoDocumentId) {

        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient client = webClientBuilder.baseUrl(baseUrl).build();

        Mono<byte[]> attempt = Mono.defer(() ->
                client.get()
                        .uri("/api/v1/documents/{id}/download", documensoDocumentId)
                        .header("Authorization", "Bearer " + apiToken)
                        .retrieve()
                        .bodyToMono(byte[].class)
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .cache());

        return attempt
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .filter(t ->
                                !(t instanceof io.github.resilience4j.circuitbreaker
                                        .CallNotPermittedException)
                                && !(t instanceof WebClientResponseException.Unauthorized)
                                && !(t instanceof DigiPresBeException)))
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "Documenso signed-PDF download failed: " + err.getMessage(), 3721, 502);
                });
    }

    /**
     * Downloads from a direct URL (absolute — the webhook's {@code downloadUrl}).
     * Uses a fresh {@link WebClient} (not the base-URL-scoped one) so the URL is
     * used as-is.
     */
    private Mono<byte[]> callDownloadUrl(String apiToken, String downloadUrl) {
        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient client = webClientBuilder.build();

        Mono<byte[]> attempt = Mono.defer(() ->
                client.get()
                        .uri(downloadUrl)
                        .header("Authorization", "Bearer " + apiToken)
                        .retrieve()
                        .bodyToMono(byte[].class)
                        .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                        .cache());

        return attempt
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .filter(t ->
                                !(t instanceof io.github.resilience4j.circuitbreaker
                                        .CallNotPermittedException)
                                && !(t instanceof WebClientResponseException.Unauthorized)
                                && !(t instanceof DigiPresBeException)))
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "Documenso signed-PDF download failed: " + err.getMessage(), 3721, 502);
                });
    }

    private Mono<DocumensoSendResult> extractDocumentId(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            // Tolerate both "documentId" and "id" field names in the response
            String docId = root.path("documentId").asText(null);
            if (docId == null || docId.isBlank()) {
                docId = root.path("id").asText(null);
            }
            if (docId == null || docId.isBlank()) {
                return Mono.error(new DigiPresBeException(
                        "Documenso send response missing documentId", 3721, 502));
            }
            return Mono.just(new DocumensoSendResult(docId));
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "Documenso send response not JSON: " + ex.getMessage(), 3721, 502));
        }
    }
}
