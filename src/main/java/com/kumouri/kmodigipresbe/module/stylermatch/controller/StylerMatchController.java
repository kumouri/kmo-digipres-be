package com.kumouri.kmodigipresbe.module.stylermatch.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchAnalytics;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchRequestBody;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchResponse;
import com.kumouri.kmodigipresbe.module.stylermatch.repository.StylerMatchRepository;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchAnalyticsService;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — the staff/coordinator surface: create a match from the desk, and work the
 * match inbox. The stylist-side twin of the T9 {@code StyleConsultController}.
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code POST /stylermatch/matches} — create a match from a {@link StylerMatchRequestBody}
 *       (the desk can pass a known {@code contactId}); returns the ranked {@link StylerMatchResponse}.</li>
 *   <li>{@code GET /stylermatch/matches} — the inbox list (newest first).</li>
 *   <li>{@code GET /stylermatch/matches/{id}} — the full match detail (4485 if absent).</li>
 * </ul>
 *
 * <h2>Gating</h2>
 * Class {@code @ConditionalOnProperty(kmosf.modules.chairfill)} (absent from the OpenAPI spec + 404 when
 * the salon flagship is off — a default-OFF staff route the FE hand-writes types for). Per-tenant
 * membership via {@link TenantModuleRegistry#requireEnabled}. Staff-accessible (the authenticated chain —
 * the {@code StyleConsultController}/{@code WaitlistBoardController} precedent).
 */
@RestController
@RequestMapping("/stylermatch")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class StylerMatchController {

    private final StylerMatchService matchService;
    private final StylerMatchRepository matches;
    private final StylerMatchAnalyticsService analyticsService;
    private final TenantModuleRegistry modules;

    public StylerMatchController(StylerMatchService matchService,
                                 StylerMatchRepository matches,
                                 StylerMatchAnalyticsService analyticsService,
                                 TenantModuleRegistry modules) {
        this.matchService = matchService;
        this.matches = matches;
        this.analyticsService = analyticsService;
        this.modules = modules;
    }

    @PostMapping("/matches")
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<StylerMatchResponse> create(@RequestBody StylerMatchRequestBody body) {
        StylerMatchRequestBody safe = body == null
                ? new StylerMatchRequestBody(null, null, null, null, null, null, null, null, null,
                        null, null, null, null)
                : body;
        return guard()
                .then(matchService.createForTenant(safe.toMatchRequest(), safe.toManualContact()))
                .map(StylerMatchResponse::from);
    }

    @GetMapping("/matches")
    public Flux<StylerMatchResponse> list() {
        return guard()
                .thenMany(TenantContextHolder.required()
                        .flatMapMany(ctx -> matches.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId())))
                .map(StylerMatchResponse::from);
    }

    @GetMapping("/matches/{id}")
    public Mono<StylerMatchResponse> detail(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> matches.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Styler match not found", 4485, 404))))
                .map(StylerMatchResponse::from);
    }

    @GetMapping("/analytics")
    public Mono<StylerMatchAnalytics> analytics() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> analyticsService.analytics(ctx.tenantId()));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(ChairFillAutoConfiguration.MODULE_KEY);
    }
}
