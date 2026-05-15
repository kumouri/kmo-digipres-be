package com.kumouri.kmodigipresbe.integration.square;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

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
        if (merchantId == null || merchantId.isBlank()) {
            log.warn("Square webhook missing merchant_id, type={}", type);
            return Mono.empty();
        }
        return findConnectionByMerchantId(merchantId)
                .flatMap(conn -> switch (type) {
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
                })
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.warn("No Square connection found for merchantId={}", merchantId)));
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
