package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.calcom.CalComWebhookService;
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
 * Unauthenticated Cal.com webhook endpoint (Phase H — H.2 / H-D1).
 *
 * <p>Tenant id is in the URL path because the caller (Cal.com) doesn't carry a
 * JWT — Cal.com authenticates via the {@code X-Cal-Signature-256} header which
 * {@link CalComWebhookService} verifies against the tenant's stored webhook
 * signing secret.
 *
 * <p>{@code /public/integrations/calcom/{tenantId}/webhook} is reached via the
 * {@code /public/**} permitAll rule. <strong>No {@code @ConditionalOnProperty}</strong>
 * — the Stripe / Documenso webhook controllers are always-on under {@code /public/**}
 * and this controller follows the same convention (H-D1).
 *
 * <p>The tenant id is resolved from the URL path <strong>only</strong> — never
 * from the webhook payload (§9 item 5 / H-D1 security invariant).
 */
@RestController
@RequestMapping("/public/integrations/calcom")
@RequiredArgsConstructor
public class CalComWebhookController {

    private final CalComWebhookService webhook;

    /**
     * Receives a Cal.com webhook for the given tenant.
     *
     * @param tenantId  the CRM tenant id (from the URL path — NEVER the payload)
     * @param signature the {@code X-Cal-Signature-256} HMAC header from Cal.com
     * @param body      the raw webhook body string
     */
    @PostMapping("/{tenantId}/webhook")
    public Mono<Void> webhook(@PathVariable String tenantId,
                              @RequestHeader(name = "X-Cal-Signature-256", required = false)
                              String signature,
                              @RequestBody(required = false) String body) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in Cal.com webhook path", 3902, 400));
        }
        return webhook.handle(parsed, signature, body == null ? "" : body);
    }
}
