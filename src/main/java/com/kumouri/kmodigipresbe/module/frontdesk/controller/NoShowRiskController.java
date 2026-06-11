package com.kumouri.kmodigipresbe.module.frontdesk.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.FrontDeskScoringJob;
import com.kumouri.kmodigipresbe.module.frontdesk.scoring.FrontDeskNoShowScoringService;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Comparator;

/**
 * FrontDesk IQ (FD-1) — PHI-free no-show risk read + manual retrain. The literal mirror of the chairfill
 * {@code NoShowRiskController}, pointed at {@link Appointment}s.
 *
 * <p>{@code @ConditionalOnProperty}-gated on {@code kmosf.modules.frontdesk.enabled} (so this controller is
 * absent from the generated OpenAPI spec when the module is off) AND every handler asserts the per-tenant
 * module membership via {@link TenantModuleRegistry#requireEnabled} (the shared 1130/1132 module-gate codes).
 *
 * <ul>
 *   <li>{@code POST /frontdesk/risk/retrain} — triggers a manual no-show retrain for the current tenant;
 *       returns 202 with the {@link FrontDeskScoringJob} record to poll. 409 (errorCode {@code 4275}) if a
 *       retrain is already running.</li>
 *   <li>{@code GET /frontdesk/risk/appointments?from&to} — upcoming appointments in the window with their
 *       {@code noShowRisk}, sorted highest-risk first (for the FD-5 risk-sorted day view). Each row carries
 *       only logistics metadata — none of it touched the clinical system.</li>
 * </ul>
 */
// Explicit bean name: chairfill has its own NoShowRiskController; Spring's default
// (decapitalized simple class name) collides fatally when both modules are enabled
// in one process (first done by the demo program's all-modules-on deployment).
@RestController("frontdeskNoShowRiskController")
@RequestMapping("/frontdesk/risk")
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@RequiredArgsConstructor
public class NoShowRiskController {

    private final FrontDeskNoShowScoringService scoringService;
    private final AppointmentRepository appointments;
    private final TenantModuleRegistry modules;

    @PostMapping("/retrain")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<FrontDeskScoringJob> retrain() {
        return guard().then(TenantContextHolder.required()
                .flatMap(ctx -> scoringService.triggerRetrain(ctx.tenantId())));
    }

    @GetMapping("/appointments")
    public Flux<Appointment> upcomingByRisk(@RequestParam Instant from, @RequestParam Instant to) {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx -> appointments
                        .findByTenantIdAndScheduledStartBetween(ctx.tenantId(), from, to)
                        .sort(Comparator.comparingDouble(NoShowRiskController::riskOf).reversed())));
    }

    /** Risk score for sorting; appointments with no stamped risk sort last (treated as -1). */
    private static double riskOf(Appointment a) {
        return a.getNoShowRisk() != null ? a.getNoShowRisk().riskScore() : -1.0;
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(FrontDeskAutoConfiguration.MODULE_KEY);
    }
}
