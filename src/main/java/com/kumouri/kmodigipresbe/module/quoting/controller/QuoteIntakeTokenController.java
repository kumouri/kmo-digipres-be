package com.kumouri.kmodigipresbe.module.quoting.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteIntakeService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
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
 * T8 (Home Services "QuoteNow") — admin endpoint to mint a {@code quote-intake} widget token. The
 * resulting token goes into the homeowner widget snippet / the truck/yard-sign QR; the public
 * {@link QuoteIntakeController} accepts submissions and resolves the tenant from the token.
 *
 * <p>Reuses the shipped {@link PublicWidgetTokenService} (the {@code ServiceRequestWidgetController}
 * precedent — QuoteNow binds no entity, so the tenant-only 3-field token is exactly right and that
 * core stays empty-diff): {@code POST /quoting/tokens} → {@code {"token": "..."}}. Gated three ways:
 * {@code @ConditionalOnProperty(kmosf.modules.quoting)} (controller absent when the module is off),
 * {@link TenantModuleRegistry#requireEnabled} (per-tenant), and {@link RoleGuard#requireRole "ADMIN"}
 * ({@code 1800} otherwise).
 */
@RestController
@RequestMapping("/quoting/tokens")
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class QuoteIntakeTokenController {

    /** Quote-intake tokens default to 180 days — a long-lived widget/QR snippet (the widget posture). */
    public static final Duration TOKEN_TTL = Duration.ofDays(180);

    private final PublicWidgetTokenService tokens;
    private final TenantModuleRegistry modules;

    public QuoteIntakeTokenController(PublicWidgetTokenService tokens, TenantModuleRegistry modules) {
        this.tokens = tokens;
        this.modules = modules;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> issue() {
        return modules.requireEnabled(QuotingAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"))
                .then(TenantContextHolder.required())
                .map(ctx -> {
                    String token = tokens.issue(ctx.tenantId(), QuoteIntakeService.WIDGET_TYPE, TOKEN_TTL);
                    return Map.of("token", token);
                });
    }
}
