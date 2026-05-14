package com.kumouri.kmodigipresbe.automation.webhook;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;

/**
 * Posts a payload to a webhook URL with HMAC signing + Resilience4j circuit
 * breaker. First real use of the
 * {@code spring-cloud-starter-circuitbreaker-reactor-resilience4j} dependency
 * declared since Phase 1.
 *
 * <p>The breaker is keyed by subscription id, so a single noisy receiver doesn't
 * trip the breaker for the rest. Defaults are baked in here for Phase 6 (50%
 * failure rate over 10 calls, 30s open window) — promote to
 * {@code application.properties} if we need per-environment tuning.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WebhookDeliveryService {

    private final WebClient.Builder webClientBuilder;
    private final CircuitBreakerRegistry breakers;
    private final ObjectMapper objectMapper;

    public Mono<Void> deliver(WebhookSubscription sub, DomainEvent event) {
        return Mono.fromCallable(() -> serialize(event))
                .flatMap(body -> postWithBreaker(sub, body, event.type()));
    }

    /**
     * Test delivery from {@code POST /api/webhooks/test} — sends a synthetic
     * payload to verify the endpoint is reachable.
     */
    public Mono<Void> testDeliver(WebhookSubscription sub) {
        String body = "{\"event\":\"kmosf.webhook.test\",\"subscriptionId\":\""
                + sub.getId() + "\"}";
        return postWithBreaker(sub, body, "kmosf.webhook.test");
    }

    private Mono<Void> postWithBreaker(WebhookSubscription sub, String body, String eventType) {
        CircuitBreaker breaker = breakers.circuitBreaker("webhook-" + sub.getId());
        String signature = sign(sub.getSecret(), body);

        WebClient client = webClientBuilder.build();
        return client.post()
                .uri(sub.getUrl())
                .header("X-KMOSF-Signature", signature)
                .header("X-KMOSF-Event", eventType)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .toBodilessEntity()
                .timeout(Duration.ofSeconds(10))
                .transformDeferred(CircuitBreakerOperator.of(breaker))
                .retryWhen(Retry.backoff(2, Duration.ofMillis(200))
                        .maxBackoff(Duration.ofSeconds(2))
                        .filter(t -> !(t instanceof io.github.resilience4j.circuitbreaker.CallNotPermittedException)))
                .doOnError(err -> log.warn(
                        "Webhook delivery to {} failed: {}", sub.getUrl(), err.toString()))
                .then();
    }

    private String serialize(DomainEvent event) {
        try {
            return objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialize event", ex);
        }
    }

    private String sign(String secret, String body) {
        if (secret == null || secret.isBlank()) return "";
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] sig = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            return "sha256=" + HexFormat.of().formatHex(sig);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC signing failed", ex);
        }
    }
}
