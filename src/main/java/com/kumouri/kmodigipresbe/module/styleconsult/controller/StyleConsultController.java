package com.kumouri.kmodigipresbe.module.styleconsult.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultAnalytics;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultInboxCard;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultStaffResponse;
import com.kumouri.kmodigipresbe.module.styleconsult.repository.StyleConsultRepository;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultAnalyticsService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — S4: the office consult-inbox + retail-attach analytics read surface
 * for the salon coordinator. Each row shows who, the read style, and how many service/retail
 * recommendations; the analytics endpoint surfaces the vision-COMPOSITION funnel (consults → retail rec
 * → booked → retail-attach rate).
 *
 * <h2>Endpoints (under {@code spring.webflux.base-path=/api/v1})</h2>
 * <ul>
 *   <li>{@code GET /styleconsult/consults} → the inbox list ({@link StyleConsultInboxCard}, newest first).</li>
 *   <li>{@code GET /styleconsult/consults/{id}} → the full {@link StyleConsultStaffResponse} detail —
 *       margin-bearing (staff-only; the prospect endpoints return the cost/margin-redacted
 *       {@code StyleConsultResponse}, security fix AI-03) (4455 if absent).</li>
 *   <li>{@code GET /styleconsult/analytics} → the {@link StyleConsultAnalytics} retail-attach funnel.</li>
 * </ul>
 *
 * <h2>Gating</h2>
 * Class {@code @ConditionalOnProperty(kmosf.modules.chairfill)} (so it is absent from the OpenAPI spec
 * + returns 404 when the salon flagship is off — a default-OFF staff route the FE hand-writes types
 * for). Per-tenant membership via {@link TenantModuleRegistry#requireEnabled("chairfill")} (1130/1132).
 * Staff-accessible (the authenticated chain — no extra RoleGuard; the {@code QuoteInboxController} /
 * {@code WaitlistBoardController} precedent).
 */
@RestController
@RequestMapping("/styleconsult")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class StyleConsultController {

    private final StyleConsultRepository consults;
    private final StyleConsultAnalyticsService analyticsService;
    private final TenantModuleRegistry modules;

    public StyleConsultController(StyleConsultRepository consults,
                                  StyleConsultAnalyticsService analyticsService,
                                  TenantModuleRegistry modules) {
        this.consults = consults;
        this.analyticsService = analyticsService;
        this.modules = modules;
    }

    @GetMapping("/consults")
    public Flux<StyleConsultInboxCard> list() {
        return guard()
                .thenMany(TenantContextHolder.required()
                        .flatMapMany(ctx -> consults.findByTenantIdOrderByCreatedAtDesc(ctx.tenantId())))
                .map(StyleConsultInboxCard::from);
    }

    @GetMapping("/consults/{id}")
    public Mono<StyleConsultStaffResponse> detail(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> consults.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Style consult not found", 4455, 404))))
                .map(StyleConsultStaffResponse::from);
    }

    @GetMapping("/analytics")
    public Mono<StyleConsultAnalytics> analytics() {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> analyticsService.analytics(ctx.tenantId()));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(ChairFillAutoConfiguration.MODULE_KEY);
    }
}
