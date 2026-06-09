package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
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

import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — the admin surface for the per-tenant {@link MidnightResponderConfig}
 * (read + upsert): the tier→campaign mapping, the handoff-delegation flag, and the after-hours window. The
 * {@code ResponderConfigController} / {@code ConciergeConversationController} precedent.
 *
 * <h2>Gating (BOTH modules — the T1 realestate+nurture posture)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)} on the class — absent from the
 *       OpenAPI spec when realestate is off (the {@code ConciergeConversationController} precedent);</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} for <strong>both</strong>
 *       {@code realestate} AND {@code responder} (1130/1132 otherwise) — T3 is the routing layer over the
 *       responder, so both must be enabled for the tenant;</li>
 *   <li>{@link RoleGuard#requireRole "STAFF"} on every endpoint (1800 otherwise).</li>
 * </ul>
 * Base path {@code /api/v1} (via {@code spring.webflux.base-path}), so these map to
 * {@code /api/v1/realestate/responder/config}.
 *
 * <h2>Errors (4380-4389 band)</h2>
 * {@code 4380} config not found on a read; {@code 4381} invalid config (a tier campaign id that does not
 * resolve to one of the tenant's {@code NurtureCampaign}s). Reused: 1130/1132 (module gate), 1800 (STAFF).
 */
@RestController
@RequestMapping("/realestate/responder/config")
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@RequiredArgsConstructor
public class MidnightResponderConfigController {

    private final MidnightResponderConfigRepository configs;
    private final NurtureCampaignRepository campaigns;
    private final TenantModuleRegistry modules;

    /** Read the tenant's Midnight Responder config (4380 if none has been created yet). */
    @GetMapping
    public Mono<MidnightResponderConfig> get() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Midnight Responder config not found for this tenant", 4380, 404))));
    }

    /**
     * Upsert the tenant's Midnight Responder config (one row per tenant). Validates each non-null tier
     * campaign id resolves to one of the tenant's {@code NurtureCampaign}s (4381). Explicit-boolean
     * find-then-update (never {@code switchIfEmpty(create)}): an existing row is updated in place
     * (preserving id/version/timestamps), else a fresh row is created.
     */
    @PutMapping
    public Mono<MidnightResponderConfig> upsert(@RequestBody MidnightResponderConfigDTO body) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> validateCampaigns(ctx.tenantId(), body)
                        .then(configs.findByTenantId(ctx.tenantId())
                                .map(java.util.Optional::of)
                                .defaultIfEmpty(java.util.Optional.empty())
                                .flatMap(existing -> {
                                    MidnightResponderConfig toSave = existing.isPresent()
                                            ? body.applyTo(existing.get())
                                            : body.toNewEntity(ctx.tenantId());
                                    return configs.save(toSave);
                                })));
    }

    /** Each non-null tier campaign id must resolve to one of the tenant's campaigns (else 4381). */
    private Mono<Void> validateCampaigns(UUID tenantId, MidnightResponderConfigDTO body) {
        return requireCampaign(tenantId, body.warmCampaignId(), "warmCampaignId")
                .then(requireCampaign(tenantId, body.coldCampaignId(), "coldCampaignId"));
    }

    private Mono<Void> requireCampaign(UUID tenantId, UUID campaignId, String field) {
        if (campaignId == null) {
            return Mono.empty();
        }
        return campaigns.findByTenantIdAndId(tenantId, campaignId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Midnight Responder config " + field + " does not resolve to a tenant campaign",
                        4381, 400)))
                .then();
    }

    /** realestate AND responder loaded + enabled for the tenant, then STAFF — the both-module order. */
    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(ResponderAutoConfiguration.MODULE_KEY))
                .then(RoleGuard.requireRole("STAFF"));
    }
}
