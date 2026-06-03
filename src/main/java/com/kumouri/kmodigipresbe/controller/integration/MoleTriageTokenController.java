package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.integration.molevision.MoleTriageService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;

/**
 * Admin endpoint to mint a <strong>tenant-scoped</strong> {@code mole-triage} widget token for the
 * public "is this a mole?" photo-triage snippet a tenant embeds on its website. Unlike the
 * per-customer {@link MoleTripwireTokenController} (whose token carries a {@code projectId}), a
 * mole-triage token binds to no entity — the public classify endpoint resolves the tenant from the
 * token claim and find-or-creates the lead, so there is no {@code {projectId}} path variable.
 * Mirrors the home-services {@code ServiceRequestTokenIssuer}: 365-day TTL, rotate by re-issuing.
 * Gated by the {@code kmosf.modules.mole-triage} switch (disabled → endpoint not registered → 404,
 * the same gate as {@link MoleTriageService}) and {@link RoleGuard#requireRole "ADMIN"}
 * ({@code 1800} if the caller is not an admin).
 */
@RestController
@RequestMapping("/integrations/mole-triage/tokens")
@ConditionalOnProperty(prefix = "kmosf.modules.mole-triage", name = "enabled",
        matchIfMissing = true)
@RequiredArgsConstructor
public class MoleTriageTokenController {

    /** Mole-triage tokens default to one year, matching {@code ServiceRequestTokenIssuer}. */
    public static final Duration TOKEN_TTL = Duration.ofDays(365);

    private final PublicWidgetTokenService tokenService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> issue() {
        return RoleGuard.requireRole("ADMIN")
                .then(TenantContextHolder.required())
                .map(ctx -> Map.of("token", tokenService.issue(
                        ctx.tenantId(), MoleTriageService.WIDGET_TYPE, TOKEN_TTL)));
    }
}
