package com.kumouri.kmodigipresbe.integration.square;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.integration.SquareWebhookEvent;
import com.kumouri.kmodigipresbe.repository.SquareWebhookEventRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

/**
 * Processes inbound Square webhook events. Currently handles:
 * <ul>
 *   <li>{@code payment.completed} — syncs to Invoice + Payment via
 *       {@link SquarePosService} and publishes {@code INVOICE_PAID}.</li>
 *   <li>{@code refund.created} — logged; full refund reconciliation is a
 *       future hardening pass.</li>
 * </ul>
 *
 * <p>Tenant resolution: the event envelope carries {@code merchant_id};
 * we look up the matching {@link IntegrationConnection} via a config-field
 * query on {@code config.merchantId}.
 */
@Slf4j
@RequiredArgsConstructor
public class SquareWebhookService {

    private final ObjectMapper objectMapper;
    private final IntegrationConnectionRepository connections;
    private final SquarePosService posService;
    private final SquareProperties props;
    private final ReactiveMongoTemplate mongo;
    private final SquareWebhookEventRepository webhookEvents;

    /**
     * Security fix BE-14 — startup assertion. When Square is enabled but its webhook signature
     * key is blank, the verifier already fails closed (BE-14), but a blank key means the webhook
     * can NEVER verify a real Square delivery — a silent misconfiguration. Log it loudly at boot
     * so it is caught in deployment rather than as silently-dropped 401s. (Loud WARN, not a hard
     * fail, so a Square-enabled box mid-setup still starts; the runtime fail-closed is the gate.)
     */
    @PostConstruct
    void assertConfigured() {
        if (props.isEnabled()
                && (props.getWebhookSignatureKey() == null || props.getWebhookSignatureKey().isBlank())) {
            log.warn("SECURITY: Square is enabled but kmosf.integrations.square.webhook-signature-key "
                    + "is BLANK — all Square webhooks will be rejected (fail-closed). Configure the "
                    + "Signature Key from the Square Developer Dashboard.");
        }
        if (props.isEnabled()
                && (props.getWebhookUrl() == null || props.getWebhookUrl().isBlank())) {
            log.warn("SECURITY: Square is enabled but kmosf.integrations.square.webhook-url is BLANK — "
                    + "webhook signatures cannot match (fail-closed).");
        }
    }

    public Mono<Void> handle(String signatureHeader, String rawBody) {
        if (!SquareSignatureVerifier.verify(
                signatureHeader, props.getWebhookUrl(), rawBody, props.getWebhookSignatureKey())) {
            return Mono.error(new DigiPresBeException(
                    "Square webhook signature invalid", 3010, 401));
        }
        return Mono.fromCallable(() -> objectMapper.readTree(rawBody))
                .flatMap(this::dispatch);
    }

    private Mono<Void> dispatch(JsonNode body) {
        String type = body.path("type").asText("");
        String merchantId = body.path("merchant_id").asText(null);
        String eventId = body.path("event_id").asText(null);
        if (merchantId == null || merchantId.isBlank()) {
            log.warn("Square webhook missing merchant_id, type={}", type);
            return Mono.empty();
        }
        return findConnectionByMerchantId(merchantId)
                // Security fix BE-14: event-id idempotency — ledger-insert-FIRST under the
                // resolved tenant's synthetic context, then dispatch. A replayed/concurrent
                // delivery's second insert hits the unique tenant_event_idx → DuplicateKeyException
                // → 200 no-op (zero double-record). An event with no id falls through to dispatch
                // (defensive — Square always sends event_id; a missing one is acknowledged, not
                // an error).
                .flatMap(conn -> dedupeThenDispatch(conn, type, eventId, body)
                        .contextWrite(TenantContextHolder.write(
                                new TenantContext(conn.getTenantId(), null, Set.of("INTEGRATION_SQUARE")))))
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("No Square connection found for merchantId={}", merchantId)));
    }

    private Mono<Void> dedupeThenDispatch(IntegrationConnection conn, String type,
                                          String eventId, JsonNode body) {
        if (eventId == null || eventId.isBlank()) {
            return dispatchByType(conn, type, body);
        }
        return webhookEvents.findByTenantIdAndSquareEventId(conn.getTenantId(), eventId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (seen) {
                        log.debug("Square webhook event {} already processed for tenant {} — no-op",
                                eventId, conn.getTenantId());
                        return Mono.empty();
                    }
                    // Ledger-insert FIRST; a concurrent duplicate loses the unique-index race.
                    return webhookEvents.save(SquareWebhookEvent.builder()
                                    .id(UUID.randomUUID())
                                    .squareEventId(eventId)
                                    .eventType(type)
                                    .receivedAt(Instant.now())
                                    .build())
                            .flatMap(saved -> dispatchByType(conn, type, body))
                            .onErrorResume(DuplicateKeyException.class, dup -> {
                                log.debug("Square webhook event {} duplicate insert (concurrent) for "
                                        + "tenant {} — no-op", eventId, conn.getTenantId());
                                return Mono.empty();
                            });
                });
    }

    private Mono<Void> dispatchByType(IntegrationConnection conn, String type, JsonNode body) {
        return switch (type) {
            case "payment.completed" -> handlePaymentCompleted(conn, body);
            case "refund.created" -> {
                log.info("Square refund.created for tenant={} — reconciliation pending",
                        conn.getTenantId());
                yield Mono.<Void>empty();
            }
            default -> {
                log.debug("Square webhook type={} ignored", type);
                yield Mono.<Void>empty();
            }
        };
    }

    private Mono<IntegrationConnection> findConnectionByMerchantId(String merchantId) {
        Query q = Query.query(
                Criteria.where("provider").is(SquareOAuthService.PROVIDER)
                        .and("config.merchantId").is(merchantId)
                        .and("status").is(IntegrationConnection.Status.ACTIVE.name()));
        return mongo.findOne(q, IntegrationConnection.class);
    }

    private Mono<Void> handlePaymentCompleted(IntegrationConnection conn, JsonNode body) {
        JsonNode data = body.path("data").path("object").path("payment");
        String paymentId = data.path("id").asText(null);
        if (paymentId == null) {
            log.warn("Square payment.completed missing payment id for tenant={}", conn.getTenantId());
            return Mono.empty();
        }
        long amountCents = data.path("amount_money").path("amount").asLong(0);
        String currency = data.path("amount_money").path("currency").asText("USD");
        String buyerEmail = data.path("buyer_email_address").asText(null);
        String note = data.path("note").asText(null);

        return posService.syncSale(conn, paymentId, amountCents, currency, buyerEmail, note)
                .then();
    }
}
