package com.kumouri.kmodigipresbe.module.dispatch.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.module.dispatch.DispatchAutoConfiguration;
import com.kumouri.kmodigipresbe.module.dispatch.controller.dto.ApplyRequest;
import com.kumouri.kmodigipresbe.module.dispatch.controller.dto.ApplyResponse;
import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchAnalytics;
import com.kumouri.kmodigipresbe.module.dispatch.model.DispatchPlan;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchAnalyticsService;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchPlanService;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchPlanService.AssignmentDecision;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.LocalDate;
import java.util.List;

/**
 * T14 (Home "DispatchIQ") — the authenticated dispatcher surface: <strong>propose</strong> an optimized
 * schedule for a day ({@code GET /dispatch/optimize}), <strong>apply</strong> the reviewed assignments
 * ({@code POST /dispatch/apply}), and read <strong>analytics</strong> ({@code GET /dispatch/analytics}).
 *
 * <p>This is NOT a new board — the EXISTING {@code GET /home-services/dispatch} board renders the committed
 * assignments after an apply. T14 is the optimization layer on top.
 *
 * <p>Gating mirrors {@code TechCopilotController}: {@code @ConditionalOnProperty(kmosf.modules.dispatch)}
 * (absent from the OpenAPI spec when off) + per-tenant module membership (1130/1132) + STAFF (1800).
 * {@code @IdempotentRoute} is on {@code apply} only (the side-effecting POST that commits assignments — the
 * {@code spawn-now}/{@code dispatch} precedent); optimize + analytics are read-only.
 */
@RestController
@RequestMapping("/dispatch")
@ConditionalOnProperty(prefix = "kmosf.modules.dispatch", name = "enabled")
@RequiredArgsConstructor
public class DispatchController {

    private final DispatchPlanService planService;
    private final DispatchAnalyticsService analyticsService;
    private final TenantModuleRegistry modules;

    @GetMapping("/optimize")
    public Mono<DispatchPlan> optimize(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            return Mono.error(new DigiPresBeException("date (ISO yyyy-MM-dd) is required", 4521, 400));
        }
        return guard().then(planService.optimize(date));
    }

    @PostMapping("/apply")
    @IdempotentRoute
    public Mono<ApplyResponse> apply(@RequestBody ApplyRequest body) {
        if (body == null || body.assignments() == null || body.assignments().isEmpty()) {
            return Mono.error(new DigiPresBeException(
                    "At least one assignment decision is required", 4522, 400));
        }
        List<AssignmentDecision> decisions = body.assignments().stream()
                .map(d -> new AssignmentDecision(
                        d == null ? null : d.workOrderId(),
                        d == null ? null : d.techUserId()))
                .toList();
        return guard()
                .then(planService.apply(body.date(), decisions))
                .map(ApplyResponse::from);
    }

    @GetMapping("/analytics")
    public Mono<DispatchAnalytics> analytics(
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        if (date == null) {
            return Mono.error(new DigiPresBeException("date (ISO yyyy-MM-dd) is required", 4521, 400));
        }
        return guard().then(analyticsService.analytics(date));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(DispatchAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"));
    }
}
