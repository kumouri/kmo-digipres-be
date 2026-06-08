package com.kumouri.kmodigipresbe.module.homeservices.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.integration.equipmentvision.EquipmentPhotoTokenService;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * Admin endpoint to mint a WorkOrder-scoped {@code equipment-photo} upload token for the Home
 * Services nameplate-photo flow (HS-2 — "Front Desk That Never Sleeps"). The resulting tokenized
 * link is what the HS-1 voicemail auto-ack SMS carries to a home-services caller ("text/upload a
 * photo of your unit here"); the public {@link EquipmentPhotoController} accepts the upload and
 * resolves the tenant + WorkOrder from the token.
 *
 * <p>Mirrors the entity-bound {@code MoleTripwireTokenController} ({@code POST .../tokens/{projectId}}
 * → token carrying a Project id) — here the bound entity is a DRAFT {@code WorkOrder}:
 * {@code POST /home-services/equipment-photo/tokens/{workOrderId}} → {@code {"token": "..."}}. The
 * WorkOrder is loaded tenant-scoped first — a token is only issued for a WorkOrder that actually
 * exists for the caller's tenant ({@code 4214} otherwise), so the link can never bind another
 * tenant's job. Gated three ways:
 * <ol>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.home-services", name="enabled")} — the
 *       server doesn't register the controller when home-services is disabled globally (the HS-1
 *       {@code MissedCallInboxController} / {@code ServiceRequestTokenIssuer} precedent);</li>
 *   <li>{@link TenantModuleRegistry#requireEnabled} — per-tenant enablement;</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} — only tenant admins mint tokens ({@code 1800} if
 *       not).</li>
 * </ol>
 */
@RestController
@RequestMapping("/home-services/equipment-photo/tokens")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class EquipmentPhotoTokenController {

    /**
     * Equipment-photo tokens default to 7 days — long enough for a caller to get to their unit and
     * snap a photo after the after-hours call, short enough that a forgotten link expires quickly
     * (a DRAFT WorkOrder is a same-week triage artifact, not a year-long widget like the
     * service-request snippet).
     */
    public static final Duration TOKEN_TTL = Duration.ofDays(7);

    private final EquipmentPhotoTokenService tokenService;
    private final WorkOrderRepository workOrders;
    private final TenantModuleRegistry modules;

    @PostMapping("/{workOrderId}")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> issue(@PathVariable UUID workOrderId) {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"))
                .then(TenantContextHolder.required())
                .flatMap(ctx -> workOrders.findById(workOrderId)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "WorkOrder not found for equipment-photo-token issuance", 4214, 404)))
                        .map(wo -> {
                            String token = tokenService.issue(ctx.tenantId(), wo.getId(), TOKEN_TTL);
                            return Map.of("token", token);
                        }));
    }
}
