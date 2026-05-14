package com.kumouri.kmodigipresbe.service.communication;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Postmark-backed transactional email. Per-tenant {@code apiToken} from
 * {@code IntegrationConnection(provider="postmark")}; falls back to the KMOSF
 * house token if the tenant has not connected their own account.
 *
 * <p>Uses {@link WebClient} (no Postmark SDK — one POST per send). The HTTP call
 * is non-blocking; no {@code Schedulers.boundedElastic()} wrapper needed, unlike
 * the Jakarta Mail path in {@code EmailService}.
 *
 * <p>Error codes (Phase 9c reservation 1700-1799):
 * <ul>
 *   <li>{@code 1700} — Postmark returned non-200 (502 propagated to caller)</li>
 *   <li>{@code 1701} — No Postmark API token configured (tenant has no
 *       IntegrationConnection and the house token is unset; 412)</li>
 * </ul>
 */
@Slf4j
@Service
public class PostmarkTransactionalEmailService implements TransactionalEmailService {

    public static final String PROVIDER = "postmark";
    private static final String API_URL = "https://api.postmarkapp.com/email";

    private final WebClient http;
    private final IntegrationConnectionRepository connections;
    private final String houseToken;

    public PostmarkTransactionalEmailService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            @Value("${kmosf.email.postmark.house-token:}") String houseToken) {
        this.http = webClientBuilder.baseUrl(API_URL).build();
        this.connections = connections;
        this.houseToken = houseToken == null ? "" : houseToken;
    }

    @Override
    public Mono<TransactionalSendResult> send(TransactionalSendRequest request) {
        return TenantContextHolder.required()
                .flatMap(ctx -> resolveToken(ctx.tenantId()))
                .flatMap(token -> postToPostmark(token, request));
    }

    private Mono<String> resolveToken(java.util.UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, PROVIDER)
                .map(IntegrationConnection::getSecrets)
                .mapNotNull(secrets -> secrets == null ? null : secrets.get("apiToken"))
                .filter(t -> t != null && !t.isBlank())
                .switchIfEmpty(Mono.defer(() -> houseToken.isBlank()
                        ? Mono.error(new DigiPresBeException(
                                "No Postmark API token configured for tenant and no house token set",
                                1701, 412))
                        : Mono.just(houseToken)));
    }

    private Mono<TransactionalSendResult> postToPostmark(String token, TransactionalSendRequest req) {
        Map<String, Object> body = new HashMap<>();
        body.put("From", req.from());
        body.put("To", String.join(",", req.to() == null ? List.<String>of() : req.to()));
        body.put("Subject", req.subject());
        if (req.htmlBody() != null) body.put("HtmlBody", req.htmlBody());
        if (req.textBody() != null) body.put("TextBody", req.textBody());
        if (req.tag() != null) body.put("Tag", req.tag());
        if (req.metadata() != null && !req.metadata().isEmpty()) {
            body.put("Metadata", req.metadata());
        }
        return http.post()
                .uri("")
                .header("Accept", MediaType.APPLICATION_JSON_VALUE)
                .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .header("X-Postmark-Server-Token", token)
                .bodyValue(body)
                .retrieve()
                .onStatus(status -> !status.is2xxSuccessful(), response -> response.bodyToMono(String.class)
                        .defaultIfEmpty("")
                        .flatMap(errBody -> Mono.error(new DigiPresBeException(
                                "Postmark send failed: " + response.statusCode() + " " + errBody,
                                1700, 502))))
                .bodyToMono(PostmarkSendResponse.class)
                .map(r -> new TransactionalSendResult(
                        r.MessageID(),
                        r.To(),
                        parseInstant(r.SubmittedAt())))
                .doOnError(err -> log.warn("Postmark send failed", err));
    }

    private static Instant parseInstant(String iso) {
        if (iso == null || iso.isBlank()) return Instant.now();
        try {
            return Instant.parse(iso);
        } catch (RuntimeException ex) {
            return Instant.now();
        }
    }

    /** Subset of the Postmark response we care about. */
    @SuppressWarnings("checkstyle:LocalVariableName")
    record PostmarkSendResponse(String MessageID, String To, String SubmittedAt,
                                int ErrorCode, String Message) {
    }
}
