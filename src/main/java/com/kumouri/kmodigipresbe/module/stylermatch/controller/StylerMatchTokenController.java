package com.kumouri.kmodigipresbe.module.stylermatch.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
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
 * T12 (Salon "StylerMatch") — admin endpoint to mint a {@code styler-match} widget token. The resulting
 * token goes into the salon's website widget snippet / a QR; the public
 * {@link StylerMatchIntakeController} accepts submissions and resolves the tenant from the token.
 *
 * <p>Reuses the shipped {@link PublicWidgetTokenService} (the T9 {@code StyleConsultTokenController}
 * precedent — StylerMatch binds no entity, so the tenant-only 3-field token is exactly right and that
 * core stays empty-diff): {@code POST /stylermatch/tokens} &rarr; {@code {"token": "..."}}. Gated three
 * ways: {@code @ConditionalOnProperty(kmosf.modules.chairfill)} (controller absent when the salon
 * flagship is off), {@link TenantModuleRegistry#requireEnabled} (per-tenant), and
 * {@link RoleGuard#requireRole "ADMIN"} ({@code 1800} otherwise).
 */
@RestController
@RequestMapping("/stylermatch/tokens")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class StylerMatchTokenController {

    /** Styler-match tokens default to 180 days — a long-lived widget/QR snippet (the widget posture). */
    public static final Duration TOKEN_TTL = Duration.ofDays(180);

    private final PublicWidgetTokenService tokens;
    private final TenantModuleRegistry modules;

    public StylerMatchTokenController(PublicWidgetTokenService tokens, TenantModuleRegistry modules) {
        this.tokens = tokens;
        this.modules = modules;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<Map<String, String>> issue() {
        return modules.requireEnabled(ChairFillAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"))
                .then(TenantContextHolder.required())
                .map(ctx -> {
                    String token = tokens.issue(ctx.tenantId(), StylerMatchService.WIDGET_TYPE, TOKEN_TTL);
                    return Map.of("token", token);
                });
    }
}
