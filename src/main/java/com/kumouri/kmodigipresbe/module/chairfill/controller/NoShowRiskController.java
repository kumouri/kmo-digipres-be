package com.kumouri.kmodigipresbe.module.chairfill.controller;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowScoringJob;
import com.kumouri.kmodigipresbe.module.chairfill.scoring.NoShowRiskScoringService;
import com.kumouri.kmodigipresbe.module.salonspa.model.Booking;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
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
 * ChairFill CF-1 — no-show risk read + manual retrain.
 *
 * <p>{@link ConditionalOnProperty}-gated on {@code kmosf.modules.chairfill.enabled} (so this
 * controller is absent from the generated OpenAPI spec when the module is off — the Home-Services
 * precedent) AND every handler asserts the per-tenant module membership via
 * {@link TenantModuleRegistry#requireEnabled(String)} (the {@code SalonBookingController} precedent;
 * surfaces the shared 1130/1132 module-gate codes).
 *
 * <ul>
 *   <li>{@code POST /chairfill/risk/retrain} — triggers a manual no-show retrain for the current
 *       tenant; returns 202 with the {@link NoShowScoringJob} record to poll. 409 (errorCode 4221)
 *       if a retrain is already running.</li>
 *   <li>{@code GET /chairfill/risk/bookings?from&to} — upcoming bookings in the window with their
 *       {@code noShowRisk}, sorted highest-risk first (for the CF-5 day-view risk column).</li>
 * </ul>
 */
@RestController
@RequestMapping("/chairfill/risk")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@RequiredArgsConstructor
public class NoShowRiskController {

    private final NoShowRiskScoringService scoringService;
    private final BookingRepository bookings;
    private final TenantModuleRegistry modules;

    @PostMapping("/retrain")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public Mono<NoShowScoringJob> retrain() {
        return guard().then(TenantContextHolder.required()
                .flatMap(ctx -> scoringService.triggerRetrain(ctx.tenantId())));
    }

    @GetMapping("/bookings")
    public Flux<Booking> upcomingByRisk(@RequestParam Instant from, @RequestParam Instant to) {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx -> bookings
                        .findByTenantIdAndScheduledStartBetween(ctx.tenantId(), from, to)
                        .sort(Comparator.comparingDouble(NoShowRiskController::riskOf).reversed())));
    }

    /** Risk score for sorting; bookings with no stamped risk sort last (treated as -1). */
    private static double riskOf(Booking b) {
        return b.getNoShowRisk() != null ? b.getNoShowRisk().riskScore() : -1.0;
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(ChairFillAutoConfiguration.MODULE_KEY);
    }
}
