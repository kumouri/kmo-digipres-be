package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.activepieces.ActivepiecesSubscriptionSeeder;
import com.kumouri.kmodigipresbe.integration.activepieces.ActivepiecesSubscriptionSeeder.SeedRequest;
import com.kumouri.kmodigipresbe.integration.activepieces.ActivepiecesSubscriptionSeeder.SeedResponse;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.List;

/**
 * Admin endpoint to seed a per-tenant Activepieces outbound-webhook
 * subscription (Phase H — H.5 / H-D5 / H-D8).
 *
 * <h2>Route</h2>
 * {@code POST /api/v1/integrations/activepieces/seed} — tenant-scoped admin;
 * requires a valid JWT (the standard admin security chain).  Tenant id is
 * resolved from the JWT via {@link TenantContextHolder} — <strong>never
 * from the request body</strong>.
 *
 * <h2>Idempotency ({@code @IdempotentRoute})</h2>
 * The {@code Idempotency-Key} header is honoured by the middleware so a
 * client retry with the same key replays the original response without
 * re-seeding.  Additionally, the seeder itself is idempotent per
 * (tenantId, targetUrl, eventType) via an explicit-boolean probe
 * (never {@code switchIfEmpty(create)}).
 *
 * <h2>Module gate</h2>
 * Unlike the {@code /public/**} webhook controllers (which are always-on),
 * this admin seed endpoint is gated on
 * {@code kmosf.modules.activepieces.enabled} ({@code matchIfMissing=true} —
 * enabled by default; set to {@code false} to disable the Activepieces
 * integration at the module level).  The plan §5 / H-D5 explicitly requires
 * the seed admin endpoint to be module-gated.
 *
 * <h2>Error codes (H-D8)</h2>
 * <ul>
 *   <li>{@code 3920} — seed target URL is missing or blank (400)</li>
 *   <li>{@code 3921} — seed not permitted / module disabled (403/404) —
 *       surfaced automatically by Spring when the bean is absent due to
 *       {@code @ConditionalOnProperty}; documented here for completeness</li>
 * </ul>
 *
 * <h2>R8 — read-only capability token (no admin scope)</h2>
 * The {@code capabilityToken} field in the request body is the
 * <strong>read-only delivery / capability token</strong> only.  An
 * Activepieces admin-scope token MUST NEVER be submitted here — R8 is
 * unconditional.  See
 * {@link ActivepiecesSubscriptionSeeder} Javadoc for the full R8 discussion.
 *
 * <h2>No live Activepieces (§7 hard boundary)</h2>
 * The seed persists a {@code WebhookSubscription} row only.  No HTTP call
 * is made to any Activepieces host at seed time.
 */
@RestController
@RequestMapping("/api/v1/integrations/activepieces")
@ConditionalOnProperty(prefix = "kmosf.modules.activepieces", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ActivepiecesController {

    private final ActivepiecesSubscriptionSeeder seeder;

    /**
     * Seed (or no-op if already seeded) a per-tenant Activepieces
     * webhook-subscription for {@code INVOICE_FINALIZED} (+ optional
     * additional event types).
     *
     * <p>The operation is idempotent: re-seeding an already-registered
     * (tenantId, targetUrl, eventType) triple returns a 200 with
     * {@code created = false}.
     *
     * @param body the seed request carrying the Activepieces webhook-trigger
     *             URL and the read-only capability token (R8 — no admin scope)
     * @return a {@link SeedResponse} projection (never the raw entity)
     */
    @IdempotentRoute
    @PostMapping("/seed")
    public Mono<SeedResponse> seed(@RequestBody SeedInputBody body) {
        if (body.targetUrl() == null || body.targetUrl().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Activepieces seed: targetUrl is required", 3920, 400));
        }

        return TenantContextHolder.required().flatMap(ctx -> {
            SeedRequest request = new SeedRequest(
                    body.targetUrl(),
                    body.capabilityToken(),
                    body.eventTypes()
            );
            return seeder.seed(ctx.tenantId(), request);
        });
    }

    /**
     * Request body for the seed endpoint.
     *
     * @param targetUrl       Activepieces webhook-trigger URL (required, non-blank).
     * @param capabilityToken <strong>Read-only delivery/capability token only
     *                        (R8 — no Activepieces admin scope).</strong>
     *                        Used as the HMAC-signing key for outbound payloads
     *                        ({@code X-KMOSF-Signature} header).
     *                        May be null or blank if no signing is desired.
     * @param eventTypes      optional list of event-type strings; if null or
     *                        empty, defaults to {@code [invoice.finalized]}.
     */
    public record SeedInputBody(
            String targetUrl,
            String capabilityToken,
            List<String> eventTypes
    ) {}
}
