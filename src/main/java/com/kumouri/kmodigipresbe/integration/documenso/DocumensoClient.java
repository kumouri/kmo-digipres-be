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
import java.util.HashMap;
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

    // Default placement of the single SIGNATURE field on the rendered contract.
    // Documenso page coordinates are percentages of the page (0-100); page 1, a
    // signature box in the lower-left. A real per-template field layout is a future
    // refinement — this guarantees a signable field exists so the send call succeeds.
    private static final int SIGNATURE_PAGE = 1;
    private static final double SIGNATURE_PAGE_X = 10.0;
    private static final double SIGNATURE_PAGE_Y = 80.0;
    private static final double SIGNATURE_PAGE_WIDTH = 30.0;
    private static final double SIGNATURE_PAGE_HEIGHT = 8.0;

    private final IntegrationConnectionRepository connections;
    private final WebClient.Builder webClientBuilder;
    private final ObjectMapper objectMapper;
    private final DocumensoProperties properties;
    private final CircuitBreakerRegistry breakers;

    /**
     * Result of a successful {@link #sendForSignature} call.
     *
     * @param documensoDocumentId the document id assigned by Documenso — the
     *                            integer document id rendered as its canonical
     *                            string form ({@code Long.toString}). Stored on
     *                            {@code Contract.documensoDocumentId} and matched by
     *                            the webhook's {@code payload.id} (also an integer
     *                            read as text) — the webhook correlation key (F-D7).
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
     * payload includes a {@code downloadUrl}, and the second hop of
     * {@link #downloadSignedPdf} — F-D7 adapter). The {@code apiToken} is still
     * required as the raw {@code Authorization} header value.
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
     * Runs the real Documenso v1 multi-step send flow (corrected against the live
     * product / saved {@code openapi-v1} spec). The single placeholder
     * {@code POST /api/v1/documents}-with-base64-PDF was wrong; the real flow is:
     * <ol>
     *   <li><b>Create</b> — {@code POST /api/v1/documents} {@code {title}} →
     *       captures the integer {@code documentId} and the presigned
     *       {@code uploadUrl} from the response.</li>
     *   <li><b>Upload</b> — {@code PUT} the rendered PDF bytes to that
     *       {@code uploadUrl} (S3 presigned PUT — no Authorization header; the URL is
     *       pre-authorized).</li>
     *   <li><b>Recipient</b> — {@code POST /api/v1/documents/{id}/recipients}
     *       {@code {name,email,role:SIGNER}} → captures the integer recipient
     *       {@code id} (the response field is {@code id}, not {@code recipientId}).</li>
     *   <li><b>Field</b> — {@code POST /api/v1/documents/{id}/fields}
     *       {@code {recipientId, type:SIGNATURE, pageNumber, pageX, pageY, pageWidth,
     *       pageHeight}} — one signature field for that recipient.</li>
     *   <li><b>Send</b> — {@code POST /api/v1/documents/{id}/send} {@code {sendEmail:true}}
     *       — emails the signer.</li>
     * </ol>
     * Each HTTP call is individually wrapped in the per-call Resilience4j
     * {@code documenso} breaker + 3-retry + timeout (the established per-call posture;
     * the create step is the only non-idempotent call, exactly as the prior single
     * POST was). The integer {@code documentId} is returned as its canonical string
     * form in {@link DocumensoSendResult} — the same string the webhook's
     * {@code payload.id} resolves to and the value stored in
     * {@code Contract.documensoDocumentId}.
     */
    private Mono<DocumensoSendResult> callSend(
            String baseUrl, String apiToken, Contract contract,
            byte[] renderedPdf, String recipientEmail, String recipientName) {

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();
        String email = recipientEmail != null ? recipientEmail : "";
        String name = recipientName != null ? recipientName : "";

        // 1. Create document → { documentId (int), uploadUrl, recipients[...] }
        Map<String, Object> createBody = new HashMap<>();
        createBody.put("title", contract.getTitle() != null ? contract.getTitle() : "Contract");

        return resilientCall(client.post()
                        .uri("/api/v1/documents")
                        .header("Authorization", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(createBody))
                .flatMap(this::parseCreateResponse)
                .flatMap(created ->
                        // 2. PUT the PDF bytes to the presigned upload URL (if provided).
                        uploadPdf(created.uploadUrl(), renderedPdf)
                                // 3. Add the signer recipient → recipient id (int)
                                .then(addRecipient(client, apiToken, created.documentId(),
                                        email, name))
                                // 4. Add a SIGNATURE field for that recipient
                                .flatMap(recipientId -> addSignatureField(
                                        client, apiToken, created.documentId(), recipientId))
                                // 5. Send for signature (emails the signer)
                                .then(sendDocument(client, apiToken, created.documentId()))
                                .thenReturn(new DocumensoSendResult(
                                        Long.toString(created.documentId()))))
                .onErrorMap(err -> {
                    if (err instanceof DigiPresBeException) return err;
                    return new DigiPresBeException(
                            "Documenso send failed: " + err.getMessage(), 3721, 502);
                });
    }

    /**
     * Step 1 → parses the {@code POST /api/v1/documents} create response, returning
     * the integer {@code documentId} (required) and the {@code uploadUrl} (presigned
     * PUT target; may be blank if the deployment streams uploads differently).
     */
    private Mono<CreatedDocument> parseCreateResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode idNode = root.path("documentId");
            // Tolerate the old "id" field name as a fallback.
            if (idNode.isMissingNode() || idNode.isNull()) {
                idNode = root.path("id");
            }
            if (idNode.isMissingNode() || idNode.isNull() || !idNode.canConvertToLong()) {
                return Mono.error(new DigiPresBeException(
                        "Documenso create response missing integer documentId", 3721, 502));
            }
            long documentId = idNode.asLong();
            String uploadUrl = root.path("uploadUrl").asText(null);
            if (uploadUrl != null && uploadUrl.isBlank()) {
                uploadUrl = null;
            }
            return Mono.just(new CreatedDocument(documentId, uploadUrl));
        } catch (Exception ex) {
            return Mono.error(new DigiPresBeException(
                    "Documenso create response not JSON: " + ex.getMessage(), 3721, 502));
        }
    }

    /**
     * Step 2 → PUTs the raw PDF bytes to the presigned {@code uploadUrl}. The URL is
     * pre-authorized (S3 presigned PUT) so it carries NO Authorization header and is
     * used as-is via a fresh (non-base-URL) {@link WebClient}. A blank/absent
     * {@code uploadUrl} is a no-op (defensive — some deployments may not return one).
     */
    private Mono<Void> uploadPdf(String uploadUrl, byte[] pdfBytes) {
        if (uploadUrl == null || uploadUrl.isBlank()) {
            return Mono.empty();
        }
        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        WebClient absolute = webClientBuilder.build();
        Mono<Void> attempt = Mono.defer(() -> absolute.put()
                .uri(uploadUrl)
                .contentType(MediaType.APPLICATION_PDF)
                .bodyValue(pdfBytes)
                .retrieve()
                .bodyToMono(Void.class)
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .cache());
        return withResilience(attempt, breaker);
    }

    /**
     * Step 3 → {@code POST /api/v1/documents/{id}/recipients} {@code {name,email,
     * role:SIGNER}}; returns the integer recipient {@code id} from the response (the
     * field is {@code id}, not {@code recipientId}).
     */
    private Mono<Long> addRecipient(WebClient client, String apiToken, long documentId,
                                    String email, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("email", email);
        body.put("role", "SIGNER");
        return resilientCall(client.post()
                        .uri("/api/v1/documents/{id}/recipients", documentId)
                        .header("Authorization", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body))
                .flatMap(responseBody -> {
                    try {
                        JsonNode root = objectMapper.readTree(responseBody);
                        JsonNode idNode = root.path("id");
                        if (idNode.isMissingNode() || idNode.isNull()
                                || !idNode.canConvertToLong()) {
                            return Mono.error(new DigiPresBeException(
                                    "Documenso recipient response missing integer id",
                                    3721, 502));
                        }
                        return Mono.just(idNode.asLong());
                    } catch (Exception ex) {
                        return Mono.error(new DigiPresBeException(
                                "Documenso recipient response not JSON: " + ex.getMessage(),
                                3721, 502));
                    }
                });
    }

    /**
     * Step 4 → {@code POST /api/v1/documents/{id}/fields} adds one {@code SIGNATURE}
     * field for the given recipient. Per the spec a single-field create requires
     * {@code recipientId, type, pageNumber, pageX, pageY, pageWidth, pageHeight}.
     * Position is a sensible default signature box on page 1 (Documenso page
     * coordinates are percentages of the page).
     */
    private Mono<Void> addSignatureField(WebClient client, String apiToken,
                                         long documentId, long recipientId) {
        Map<String, Object> body = new HashMap<>();
        body.put("recipientId", recipientId);
        body.put("type", "SIGNATURE");
        body.put("pageNumber", SIGNATURE_PAGE);
        body.put("pageX", SIGNATURE_PAGE_X);
        body.put("pageY", SIGNATURE_PAGE_Y);
        body.put("pageWidth", SIGNATURE_PAGE_WIDTH);
        body.put("pageHeight", SIGNATURE_PAGE_HEIGHT);
        return resilientCall(client.post()
                        .uri("/api/v1/documents/{id}/fields", documentId)
                        .header("Authorization", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body))
                .then();
    }

    /**
     * Step 5 → {@code POST /api/v1/documents/{id}/send} {@code {sendEmail:true}} —
     * this is the call that actually emails the signer the signing link.
     */
    private Mono<Void> sendDocument(WebClient client, String apiToken, long documentId) {
        Map<String, Object> body = new HashMap<>();
        body.put("sendEmail", true);
        return resilientCall(client.post()
                        .uri("/api/v1/documents/{id}/send", documentId)
                        .header("Authorization", apiToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .bodyValue(body))
                .then();
    }

    /**
     * Wraps a prepared {@link WebClient.RequestHeadersSpec} call into a resilient
     * {@code Mono<String>} body: fresh {@code Mono.defer} per attempt + {@code .cache()}
     * (so a retry re-subscription doesn't hit a released response body — the
     * {@code QuickBooksInvoiceSync}/{@code StripeCheckoutService} precedent), the
     * per-call {@code documenso} circuit breaker, 3-retry backoff, and the request
     * timeout. {@code Unauthorized} / {@code DigiPresBeException} /
     * {@code CallNotPermittedException} are not retried.
     */
    private Mono<String> resilientCall(WebClient.RequestHeadersSpec<?> spec) {
        CircuitBreaker breaker = breakers.circuitBreaker(CB_NAME);
        Mono<String> attempt = Mono.defer(() -> spec
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(properties.getRequestTimeoutSeconds()))
                .cache());
        return withResilience(attempt, breaker);
    }

    /**
     * Applies the shared circuit-breaker + filtered 3-retry-backoff to a per-attempt
     * (already {@code .cache()}d) {@code Mono}.
     */
    private <T> Mono<T> withResilience(Mono<T> attempt, CircuitBreaker breaker) {
        return attempt
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(Retry.backoff(3, Duration.ofSeconds(1))
                        .maxBackoff(Duration.ofSeconds(4))
                        .filter(t ->
                                !(t instanceof io.github.resilience4j.circuitbreaker
                                        .CallNotPermittedException)
                                && !(t instanceof WebClientResponseException.Unauthorized)
                                && !(t instanceof DigiPresBeException)));
    }

    /** Parsed result of the create-document step. */
    private record CreatedDocument(long documentId, String uploadUrl) {}

    /**
     * Downloads the signed PDF via {@code GET /api/v1/documents/{id}/download}.
     * Used when the webhook payload does not include a {@code downloadUrl}.
     *
     * <p><b>Corrected against the spec (brief contradiction recorded):</b> the brief
     * said this endpoint returns the signed PDF bytes directly. The saved
     * {@code openapi-v1} spec (operation {@code downloadSignedDocument}, summary
     * "Download a signed document when the storage transport is S3") shows it returns
     * JSON {@code { "downloadUrl": "<presigned URL>" }} — NOT raw bytes. So this is a
     * two-hop: GET the JSON, then fetch the bytes from the returned presigned URL via
     * {@link #callDownloadUrl}. The placeholder's {@code bodyToMono(byte[].class)}
     * against this endpoint would have parsed the JSON envelope as the "PDF".
     */
    private Mono<byte[]> callDownload(
            String baseUrl, String apiToken, String documensoDocumentId) {

        WebClient client = webClientBuilder.baseUrl(baseUrl).build();

        return resilientCall(client.get()
                        .uri("/api/v1/documents/{id}/download", documensoDocumentId)
                        .header("Authorization", apiToken)
                        .accept(MediaType.APPLICATION_JSON))
                .flatMap(responseBody -> {
                    String downloadUrl;
                    try {
                        JsonNode root = objectMapper.readTree(responseBody);
                        downloadUrl = root.path("downloadUrl").asText(null);
                    } catch (Exception ex) {
                        return Mono.<String>error(new DigiPresBeException(
                                "Documenso download response not JSON: " + ex.getMessage(),
                                3721, 502));
                    }
                    if (downloadUrl == null || downloadUrl.isBlank()) {
                        return Mono.<String>error(new DigiPresBeException(
                                "Documenso download response missing downloadUrl", 3721, 502));
                    }
                    return Mono.just(downloadUrl);
                })
                // Second hop: fetch the bytes from the presigned URL.
                .flatMap(url -> callDownloadUrl(apiToken, url))
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
                        // Documenso v1 auth: the raw API token is the Authorization
                        // header VALUE itself (the spec's apiKey-in-header scheme).
                        // NOT "Bearer <token>", NOT "api_<token>".
                        .header("Authorization", apiToken)
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

}
