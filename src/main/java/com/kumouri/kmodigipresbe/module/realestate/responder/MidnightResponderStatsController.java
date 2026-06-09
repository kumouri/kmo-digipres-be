package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversation;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeConversationRepository;
import com.kumouri.kmodigipresbe.module.realestate.model.ConciergeTurn;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — the <strong>response-latency stats</strong> read (the
 * "&lt;30s, 24/7" demo stat). Aggregates the per-turn received→replied latency the
 * {@code ConciergeInboundRouter} stamps onto assistant turns, plus the share of buyer turns received
 * outside the tenant's configured business-hours window ("answered while you slept"). Pure in-service
 * aggregation over the tenant's {@code ConciergeConversation}s — no new collection.
 *
 * <h2>Gating (BOTH modules — the {@code MidnightResponderConfigController} posture)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)} — absent from the OpenAPI spec
 *       when realestate is off (the {@code ConciergeConversationController} precedent);</li>
 *   <li>per-tenant {@link TenantModuleRegistry#requireEnabled(String)} for BOTH {@code realestate} AND
 *       {@code responder};</li>
 *   <li>{@link RoleGuard#requireRole "STAFF"}.</li>
 * </ul>
 * Maps to {@code GET /api/v1/realestate/responder/latency-stats}. An optional {@code zoneId} query param
 * (e.g. {@code America/Chicago}) sets the local zone for the after-hours classification; absent ⇒ UTC.
 */
@RestController
@RequestMapping("/realestate/responder")
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@RequiredArgsConstructor
public class MidnightResponderStatsController {

    private final ConciergeConversationRepository conversations;
    private final MidnightResponderConfigRepository configs;
    private final TenantModuleRegistry modules;

    /**
     * Aggregated responder-latency stats for the tenant. p50/p95/max over the assistant turns'
     * received→replied latency; the after-hours share over buyer turns' receipt local-hour vs the
     * configured business-hours window (default 8..18). {@code zoneId} optionally sets the local zone.
     */
    @GetMapping("/latency-stats")
    public Mono<MidnightResponderLatencyStats> latencyStats(
            @RequestParam(name = "zoneId", required = false) String zoneId) {
        ZoneId zone = resolveZone(zoneId);
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> configs.findByTenantId(ctx.tenantId())
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.empty())
                        .flatMap(cfg -> {
                            int startHour = cfg.map(MidnightResponderConfig::getAfterHoursStartHour)
                                    .orElse(MidnightResponderConfig.DEFAULT_AFTER_HOURS_START);
                            int endHour = cfg.map(MidnightResponderConfig::getAfterHoursEndHour)
                                    .orElse(MidnightResponderConfig.DEFAULT_AFTER_HOURS_END);
                            return aggregate(ctx.tenantId(), zone, startHour, endHour);
                        }));
    }

    private Mono<MidnightResponderLatencyStats> aggregate(UUID tenantId, ZoneId zone,
                                                          int startHour, int endHour) {
        List<Long> latencies = new ArrayList<>();
        long[] afterHours = {0L};
        long[] totalBuyer = {0L};
        return conversations.findByTenantIdOrderByLastInboundAtDesc(tenantId)
                .doOnNext(conv -> accumulate(conv, zone, startHour, endHour, latencies, afterHours, totalBuyer))
                .then(Mono.fromSupplier(() ->
                        build(latencies, afterHours[0], totalBuyer[0], startHour, endHour)));
    }

    private static void accumulate(ConciergeConversation conv, ZoneId zone, int startHour, int endHour,
                                   List<Long> latencies, long[] afterHours, long[] totalBuyer) {
        if (conv.getTurns() == null) {
            return;
        }
        for (ConciergeTurn t : conv.getTurns()) {
            if (t.getRole() == ConciergeTurn.Role.ASSISTANT && t.getLatencyMs() != null) {
                latencies.add(t.getLatencyMs());
            }
            if (t.getRole() == ConciergeTurn.Role.BUYER) {
                Instant received = t.getReceivedAt() != null ? t.getReceivedAt() : t.getAt();
                if (received != null) {
                    totalBuyer[0]++;
                    int localHour = received.atZone(zone).getHour();
                    if (isAfterHours(localHour, startHour, endHour)) {
                        afterHours[0]++;
                    }
                }
            }
        }
    }

    /** True when {@code hour} falls OUTSIDE the business-hours window [start, end). Handles wrap (start>end). */
    static boolean isAfterHours(int hour, int startHour, int endHour) {
        if (startHour == endHour) {
            // Degenerate window ⇒ treat all hours as business hours (nothing after-hours).
            return false;
        }
        boolean inBusiness = (startHour < endHour)
                ? (hour >= startHour && hour < endHour)
                // wrap-around window (e.g. 22..6) — business hours span midnight
                : (hour >= startHour || hour < endHour);
        return !inBusiness;
    }

    private static MidnightResponderLatencyStats build(List<Long> latencies, long afterHours,
                                                       long totalBuyer, int startHour, int endHour) {
        Collections.sort(latencies);
        long p50 = percentile(latencies, 50);
        long p95 = percentile(latencies, 95);
        long max = latencies.isEmpty() ? 0L : latencies.get(latencies.size() - 1);
        double share = totalBuyer == 0 ? 0.0 : (double) afterHours / (double) totalBuyer;
        return new MidnightResponderLatencyStats(
                latencies.size(), p50, p95, max, afterHours, totalBuyer, share, startHour, endHour);
    }

    /** Nearest-rank percentile over a pre-sorted ascending list (0 when empty). */
    static long percentile(List<Long> sorted, int pct) {
        if (sorted.isEmpty()) {
            return 0L;
        }
        int rank = (int) Math.ceil(pct / 100.0 * sorted.size());
        int idx = Math.min(Math.max(rank - 1, 0), sorted.size() - 1);
        return sorted.get(idx);
    }

    private static ZoneId resolveZone(String zoneId) {
        if (zoneId == null || zoneId.isBlank()) {
            return ZoneOffset.UTC;
        }
        try {
            return ZoneId.of(zoneId.trim());
        } catch (java.time.DateTimeException ex) {
            return ZoneOffset.UTC;
        }
    }

    /** realestate AND responder loaded + enabled for the tenant, then STAFF. */
    private Mono<Void> guard() {
        return modules.requireEnabled(RealEstateAutoConfiguration.MODULE_KEY)
                .then(modules.requireEnabled(ResponderAutoConfiguration.MODULE_KEY))
                .then(RoleGuard.requireRole("STAFF"));
    }
}
