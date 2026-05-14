package com.kumouri.kmodigipresbe.module.homeservices.widget;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
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
 * Phase 10e — admin endpoint to issue service-request widget tokens for embedding
 * in a tenant's public website. Token TTL is fixed at 365 days; tenants who
 * decommission the widget should rotate by issuing a new one (and ideally rotate
 * the {@code kmosf.security.widget-token-secret} on a longer cycle).
 *
 * <p>Gated three ways:
 * <ol>
 *   <li>Class-level {@code @ConditionalOnProperty} — server doesn't even
 *       register the controller when home-services is disabled globally.</li>
 *   <li>{@link TenantModuleRegistry#requireEnabled} — per-tenant enablement
 *       (a tenant on a multi-tenant install must explicitly enable
 *       home-services).</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} — only tenant admins can mint
 *       tokens; rate-limited by the staff chain's existing throttles (no
 *       widget-specific throttle yet — see follow-up note in
 *       {@code ServiceRequestWidgetController}).</li>
 * </ol>
 */
@RestController
@RequestMapping("/home-services/widgets/service-request/tokens")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class ServiceRequestTokenIssuer {

    /**
     * Service-request tokens default to one year. Long enough for an embedded
     * snippet to live across most website refresh cycles; short enough that a
     * forgotten widget eventually expires.
     */
    public static final Duration TOKEN_TTL = Duration.ofDays(365);

    private final PublicWidgetTokenService tokenService;
    private final TenantModuleRegistry modules;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> issue() {
        return modules.requireEnabled(HomeServicesAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"))
                .then(TenantContextHolder.required())
                .map(ctx -> {
                    String token = tokenService.issue(
                            ctx.tenantId(),
                            ServiceRequestWidgetController.WIDGET_TYPE,
                            TOKEN_TTL);
                    return Map.of("token", token);
                });
    }
}
