package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Anonymous webhook endpoint for Intuit's inbound notifications. Mirrors the
 * Phase 8 Stripe webhook pattern: tenant id in the URL path, signature
 * (HMAC-SHA256 of the raw body, keyed by the tenant's stored
 * {@code webhookVerifierToken}) is the auth signal.
 *
 * <p>Reachable under {@code /public/integrations/quickbooks/{tenantId}/webhook}
 * via the {@code /public/**} permitAll matcher established in Phase 4.
 */
@RestController
@RequestMapping("/public/integrations/quickbooks")
@ConditionalOnProperty(prefix = "kmosf.integrations.quickbooks", name = "enabled")
@RequiredArgsConstructor
public class QuickBooksWebhookController {

    private final QuickBooksWebhookService webhook;

    @PostMapping("/{tenantId}/webhook")
    public Mono<Void> webhook(@PathVariable String tenantId,
                              @RequestHeader(name = "intuit-signature", required = false) String signature,
                              @RequestBody(required = false) String body) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in QBO webhook path", 2813, 400));
        }
        return webhook.handle(parsed, signature, body == null ? "" : body);
    }
}
