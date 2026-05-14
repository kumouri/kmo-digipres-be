package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.stripe.StripeWebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Unauthenticated webhook endpoint. Tenant id is in the URL path because the
 * caller (Stripe) doesn't carry a JWT — Stripe authenticates via the
 * {@code Stripe-Signature} header which {@link StripeWebhookService} verifies
 * against the tenant's stored webhook signing secret.
 *
 * <p>{@code /public/integrations/stripe/{tenantId}/webhook} is reached via the
 * {@code /public/**} permitAll rule established in Phase 4.
 */
@RestController
@RequestMapping("/public/integrations/stripe")
@RequiredArgsConstructor
public class StripeWebhookController {

    private final StripeWebhookService webhook;

    @PostMapping("/{tenantId}/webhook")
    public Mono<Void> webhook(@PathVariable String tenantId,
                              @RequestHeader(name = "Stripe-Signature", required = false) String signature,
                              @RequestBody(required = false) String body) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in webhook path", 2520, 400));
        }
        return webhook.handle(parsed, signature, body == null ? "" : body);
    }
}
