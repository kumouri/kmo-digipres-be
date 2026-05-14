package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.postmark.PostmarkWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Anonymous Postmark webhook endpoint. Mirrors the Phase 8 Stripe webhook
 * pattern: tenant id in URL path, Basic Auth password verified against the
 * tenant's {@code IntegrationConnection} before any side effect is applied.
 *
 * <p>Reachable under {@code /public/integrations/postmark/{tenantId}/webhook}
 * via the {@code /public/**} permitAll rule.
 */
@RestController
@RequestMapping("/public/integrations/postmark")
@RequiredArgsConstructor
public class PostmarkWebhookController {

    private final PostmarkWebhookService webhook;

    @PostMapping("/{tenantId}/webhook")
    public Mono<Void> webhook(
            @PathVariable String tenantId,
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization,
            @RequestBody(required = false) String body) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in webhook path", 1706, 400));
        }
        return webhook.handle(parsed, authorization, body == null ? "" : body);
    }
}
