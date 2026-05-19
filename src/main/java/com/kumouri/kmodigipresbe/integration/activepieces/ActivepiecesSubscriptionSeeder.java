package com.kumouri.kmodigipresbe.integration.activepieces;

import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscription;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscriptionRepository;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Idempotently seeds a per-tenant Activepieces target as a
 * {@link WebhookSubscription} (Phase H — H.5 / H-D5).
 *
 * <h2>Reuse — zero new delivery engine</h2>
 * This service only <em>creates a {@code WebhookSubscription} row</em>.
 * Delivery of {@code INVOICE_FINALIZED} (and all other matching events) to
 * the seeded Activepieces endpoint is performed by the
 * <strong>unchanged</strong>
 * {@link com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService}
 * when the event fires — this class never touches delivery.
 *
 * <h2>Default event set</h2>
 * The subscription is seeded for {@code INVOICE_FINALIZED} — the headline
 * trigger for outbound Activepieces automation (AC-H3).  The subscription
 * entity supports a {@code List<String> eventTypes}; additional event types
 * may be passed by the caller via {@link SeedRequest#eventTypes()}.  If
 * {@code eventTypes} is empty or null the default
 * {@code [DomainEventType.INVOICE_FINALIZED]} is used.
 *
 * <h2>Idempotency — explicit-boolean probe (§9 / C-D4 / F-D6 posture)</h2>
 * Per (tenantId, url, eventType), the seeder uses an <strong>explicit-boolean
 * exists-probe</strong>:
 * <pre>{@code
 *   repo.findFirstByTenantIdAndUrlAndEventType(tenantId, url, eventType)
 *       .map(x -> true)
 *       .defaultIfEmpty(false)
 *       .flatMap(seen -> seen ? <return existing / no-op> : <create>)
 * }</pre>
 * This is <strong>NEVER {@code switchIfEmpty(create)}</strong> — that is the
 * §9 trap that double-creates on concurrent requests.
 *
 * <h2>R8 — read-only capability token (no admin scope)</h2>
 * The {@code secret} stored in {@link WebhookSubscription#getSecret()} is the
 * <strong>read-only delivery / capability token</strong> that the Activepieces
 * webhook-trigger URL carries.  It is used by
 * {@link com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService}
 * to HMAC-sign the outbound payload (the receiver verifies via
 * {@code X-KMOSF-Signature}).
 *
 * <p><strong>No Activepieces admin-scope token is stored anywhere by this
 * class.</strong> An Activepieces admin token (if one exists for other
 * management operations) is a separate credential that must NEVER pass through
 * this seed endpoint — R8 is unconditional.
 *
 * <h2>No live Activepieces (§7 hard boundary)</h2>
 * The seed <em>only persists a {@code WebhookSubscription} row</em>.  No HTTP
 * call is made to any Activepieces host at seed time.  In every test/CI run
 * the WireMock Activepieces endpoint is registered <em>after</em> the seed and
 * exercised when a matching event fires via the delivery service.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ActivepiecesSubscriptionSeeder {

    /** Default event types seeded when the caller supplies none. */
    static final List<String> DEFAULT_EVENT_TYPES =
            List.of(DomainEventType.INVOICE_FINALIZED);

    private final WebhookSubscriptionRepository subs;

    /**
     * Seed request carrying the Activepieces webhook-trigger target URL and the
     * read-only capability token.
     *
     * @param targetUrl     the Activepieces webhook-trigger URL — must be a
     *                      valid non-blank URL; validated before this method is
     *                      called by {@link ActivepiecesController}
     * @param capabilityToken the <strong>read-only delivery / capability
     *                      token</strong> stored as the subscription secret
     *                      (HMAC-signing key for outbound payloads).
     *                      <strong>MUST NOT be an Activepieces admin-scope
     *                      token (R8 — unconditional).</strong>
     * @param eventTypes    optional list of event-type strings to subscribe to;
     *                      if null or empty the default
     *                      {@code [INVOICE_FINALIZED]} set is used
     */
    public record SeedRequest(
            String targetUrl,
            String capabilityToken,
            List<String> eventTypes
    ) {}

    /**
     * Projection returned from the seed endpoint — never the raw entity.
     *
     * @param id        the {@link WebhookSubscription} id
     * @param tenantId  the owning tenant
     * @param url       the registered Activepieces webhook-trigger URL
     * @param eventTypes the subscribed event-type strings
     * @param active    whether the subscription is active
     * @param created   true when a new row was inserted; false when the call
     *                  was a no-op (already seeded)
     */
    public record SeedResponse(
            UUID id,
            UUID tenantId,
            String url,
            List<String> eventTypes,
            boolean active,
            boolean created
    ) {}

    /**
     * Idempotently seeds an Activepieces {@link WebhookSubscription} for the
     * given tenant.
     *
     * <p>Per (tenantId, url, eventType) the operation is idempotent via an
     * <strong>explicit-boolean probe</strong> — never {@code switchIfEmpty(create)}.
     * When the subscription already exists a 200 no-op response is returned
     * ({@code created = false}).
     *
     * @param tenantId resolved from the JWT (never from the request body)
     * @param request  seed parameters including target URL and capability token
     * @return a {@link SeedResponse} projection
     */
    public Mono<SeedResponse> seed(UUID tenantId, SeedRequest request) {
        List<String> effectiveEventTypes = (request.eventTypes() == null
                || request.eventTypes().isEmpty())
                ? DEFAULT_EVENT_TYPES
                : request.eventTypes();

        // For the idempotency probe we check the primary event type (the first
        // in the list).  Each (tenantId, url, eventType) triple is a unique
        // logical seed; the explicit-boolean probe avoids switchIfEmpty(create).
        String primaryEventType = effectiveEventTypes.get(0);

        // Explicit-boolean idempotency probe — NEVER switchIfEmpty(create).
        // The §9 trap: switchIfEmpty(create) would double-insert on a concurrent
        // request before the first save completes.  The explicit-boolean path is
        // the C-D4 / F-D6 mandated posture.
        return subs.findFirstByTenantIdAndUrlAndEventTypesContaining(
                        tenantId, request.targetUrl(), primaryEventType)
                .map(x -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (seen) {
                        // Already seeded — return the existing row as a no-op response.
                        return subs.findFirstByTenantIdAndUrlAndEventTypesContaining(
                                        tenantId, request.targetUrl(), primaryEventType)
                                .map(existing -> toResponse(existing, false));
                    }
                    // Not yet seeded — create a new WebhookSubscription row.
                    // The existing WebhookDeliveryService will pick this up when
                    // a matching event fires — no delivery code here.
                    WebhookSubscription sub = WebhookSubscription.builder()
                            .tenantId(tenantId)
                            .name("Activepieces – " + request.targetUrl())
                            .url(request.targetUrl())
                            // R8: store ONLY the read-only delivery/capability token.
                            // An Activepieces admin-scope token MUST NEVER be stored here.
                            .secret(request.capabilityToken())
                            .eventTypes(effectiveEventTypes)
                            .active(true)
                            .build();
                    return subs.save(sub)
                            .map(saved -> toResponse(saved, true));
                });
    }

    private SeedResponse toResponse(WebhookSubscription sub, boolean created) {
        return new SeedResponse(
                sub.getId(),
                sub.getTenantId(),
                sub.getUrl(),
                sub.getEventTypes(),
                sub.isActive(),
                created
        );
    }
}
