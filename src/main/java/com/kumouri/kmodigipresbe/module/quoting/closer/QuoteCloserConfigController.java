package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

/**
 * T11 (Home "QuoteCloser") — the admin surface for the per-tenant {@link QuoteCloserConfig} (read +
 * upsert): the un-accepted-quote nurture campaign + the window. The {@code MidnightResponderConfigController}
 * (T3) precedent.
 *
 * <h2>Gating (BOTH modules — the T1/T3 both-module posture)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.quoting.enabled)} on the class — absent from the
 *       OpenAPI spec when quoting is off (the {@code QuoteInboxController} precedent; quoting is default-OFF
 *       with no {@code matchIfMissing});</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} for <strong>both</strong>
 *       {@code quoting} AND {@code nurture} (1130/1132 otherwise) — QuoteCloser is the composition layer over
 *       quoting + the nurture cadence, so both must be enabled for the tenant;</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise) — a config surface.</li>
 * </ul>
 * Base path {@code /api/v1} (via {@code spring.webflux.base-path}) ⇒ {@code /api/v1/quoting/quote-closer/config}.
 *
 * <h2>Errors (4470-4479 band)</h2>
 * {@code 4470} config not found on a read; {@code 4471} invalid config (a {@code campaignId} that does not
 * resolve to one of the tenant's {@code NurtureCampaign}s). Reused: 1130/1132 (module gate), 1800 (ADMIN).
 */
@RestController
@RequestMapping("/quoting/quote-closer/config")
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
@RequiredArgsConstructor
public class QuoteCloserConfigController {

    private final QuoteCloserConfigRepository configs;
    private final NurtureCampaignRepository campaigns;
    private final TenantModuleRegistry modules;

    /** Read the tenant's QuoteCloser config (4470 if none has been created yet). */
    @GetMapping
    public Mono<QuoteCloserConfigDTO> get() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "QuoteCloser config not found for this tenant", 4470, 404))))
                .map(QuoteCloserConfigDTO::from);
    }

    /**
     * Upsert the tenant's QuoteCloser config (one row per tenant). Validates the non-null {@code campaignId}
     * resolves to one of the tenant's {@code NurtureCampaign}s (4471). Explicit-boolean find-then-update
     * (never {@code switchIfEmpty(create)}): an existing row is updated in place (preserving
     * id/version/timestamps), else a fresh row is created.
     */
    @PutMapping
    public Mono<QuoteCloserConfigDTO> upsert(@RequestBody QuoteCloserConfigDTO body) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> validateCampaign(ctx.tenantId(), body)
                        .then(configs.findByTenantId(ctx.tenantId())
                                .map(Optional::of)
                                .defaultIfEmpty(Optional.empty())
                                .flatMap(existing -> {
                                    QuoteCloserConfig toSave = existing.isPresent()
                                            ? body.applyTo(existing.get())
                                            : body.toNewEntity(ctx.tenantId());
                                    return configs.save(toSave);
                                })))
                .map(QuoteCloserConfigDTO::from);
    }

    /** A non-null {@code campaignId} must resolve to one of the tenant's campaigns (else 4471). */
    private Mono<Void> validateCampaign(UUID tenantId, QuoteCloserConfigDTO body) {
        if (body.campaignId() == null) {
            return Mono.empty();
        }
        return campaigns.findByTenantIdAndId(tenantId, body.campaignId())
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "QuoteCloser config campaignId does not resolve to a tenant campaign", 4471, 400)))
                .then();
    }

    /** quoting AND nurture loaded + enabled for the tenant, then ADMIN — the both-module order. */
    private Mono<Void> guard() {
        return modules.requireEnabled(QuotingAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(NurtureAutoConfiguration.MODULE_KEY))
                .then(RoleGuard.requireRole("ADMIN"));
    }
}
