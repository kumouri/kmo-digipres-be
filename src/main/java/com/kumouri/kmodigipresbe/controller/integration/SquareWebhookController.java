package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.integration.square.SquareWebhookService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Anonymous webhook receiver for Square POS events. Square sends all events
 * to a single registered endpoint; tenant resolution is by {@code merchant_id}
 * in the event envelope matched against the stored
 * {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection}.
 *
 * <p>Signature verification uses HMAC-SHA256(notificationUrl + rawBody,
 * webhookSignatureKey) — see {@link com.kumouri.kmodigipresbe.integration.square.SquareSignatureVerifier}.
 */
@Slf4j
@RestController
@RequestMapping("/public/integrations/square/webhook")
@ConditionalOnProperty(prefix = "kmosf.integrations.square", name = "enabled")
@RequiredArgsConstructor
public class SquareWebhookController {

    private final SquareWebhookService webhookService;

    @PostMapping
    @ResponseStatus(HttpStatus.OK)
    public Mono<Void> receive(
            @RequestHeader(value = "x-square-hmacsha256-signature", required = false) String signature,
            @RequestBody String rawBody) {
        return webhookService.handle(signature, rawBody)
                .doOnError(e -> log.warn("Square webhook processing error: {}", e.getMessage()));
    }
}
