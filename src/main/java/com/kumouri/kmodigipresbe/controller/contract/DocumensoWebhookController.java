package com.kumouri.kmodigipresbe.controller.contract;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.service.contract.DocumensoWebhookService;
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
 * Unauthenticated Documenso webhook endpoint (Phase F — F-D7). Mirrors
 * {@code StripeWebhookController} structure exactly.
 *
 * <p>{@code POST /public/integrations/documenso/{tenantId}/webhook} is reached
 * via the {@code /public/**} permitAll rule established in Phase 4 — the same
 * permit-all path used by the Stripe webhook controller.
 *
 * <h2>NOT module-gated</h2>
 * This controller carries <strong>no {@code @ConditionalOnProperty}</strong> —
 * identical to {@code StripeWebhookController}. The per-tenant
 * {@code IntegrationConnection(provider="documenso")} is the real gate: if the
 * tenant has no Documenso connection, {@code DocumensoWebhookService} returns
 * {@code 3712 / 404} without any side effect. A module flag on the controller
 * would silently 404 a tenant's signed-document callback if the flag flipped.
 *
 * <h2>Signature header (F-D7 documented assumption)</h2>
 * The exact header name is assumed to be {@code X-Documenso-Signature}. This is
 * the <strong>only place the header name is referenced</strong> — correcting it
 * against a real Documenso deployment is a one-line change here.
 *
 * <h2>Tenant resolution</h2>
 * Tenant id comes from the URL path; the payload's claimed tenant (if any) is
 * NEVER trusted (§9 item 5).
 */
@RestController
@RequestMapping("/public/integrations/documenso")
@RequiredArgsConstructor
public class DocumensoWebhookController {

    private final DocumensoWebhookService webhook;

    /**
     * Receives a Documenso webhook delivery for the given tenant.
     *
     * @param tenantId  tenant UUID from the URL path (validated here — 3711 if invalid)
     * @param signature value of the assumed {@code X-Documenso-Signature} header
     *                  (may be absent — treated as invalid by the verifier → 3710/401)
     * @param body      raw request body (may be absent — treated as empty by the service)
     */
    @PostMapping("/{tenantId}/webhook")
    public Mono<Void> webhook(
            @PathVariable String tenantId,
            @RequestHeader(name = "X-Documenso-Signature", required = false) String signature,
            @RequestBody(required = false) String body) {
        UUID parsed;
        try {
            parsed = UUID.fromString(tenantId);
        } catch (IllegalArgumentException ex) {
            return Mono.error(new DigiPresBeException(
                    "Invalid tenant id in webhook path", 3711, 400));
        }
        return webhook.handle(parsed, signature, body == null ? "" : body);
    }
}
