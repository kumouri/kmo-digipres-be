package com.kumouri.kmodigipresbe.service.ai;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.ai.AiUsage;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.AiUsageRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Enforces per-tenant monthly AI budget caps and records spend after each call.
 *
 * <p>Flow:
 * <ol>
 *   <li>{@link #checkBudget()} — called before any AI provider request. Reads
 *       the current month's {@link AiUsage} and the tenant's
 *       {@link Tenant#getAiBudgetUsd()} cap. Throws
 *       {@link DigiPresBeException} {@code errorCode=1200, status=429} when
 *       the cap is exhausted (default cap is {@link BigDecimal#ZERO} per the
 *       opt-in policy decision).</li>
 *   <li>{@link #record(long, long, BigDecimal)} — called after a successful AI
 *       call. Bumps {@code totalTokensIn / totalTokensOut / totalUsd /
 *       callCount} on the month's usage record, creating it if absent.</li>
 * </ol>
 *
 * <p>Error code 1200 sits in the reserved {@code 1200-1299} AI range
 * (re-allocated from the plan's original 1300 slot which Phase 3 already
 * occupies with RRULE errors).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiUsageRecorder {

    private static final DateTimeFormatter YEAR_MONTH = DateTimeFormatter.ofPattern("yyyy-MM");

    private final AiUsageRepository aiUsage;
    private final TenantRepository tenants;
    private final org.springframework.beans.factory.ObjectProvider<Clock> clockProvider;

    public Mono<Void> checkBudget() {
        return TenantContextHolder.required()
                .flatMap(ctx -> tenants.findById(ctx.tenantId())
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "Tenant not found for AI budget check", 1201, 404)))
                        .flatMap(tenant -> checkAgainstSpend(ctx.tenantId(), tenant)));
    }

    private Mono<Void> checkAgainstSpend(UUID tenantId, Tenant tenant) {
        BigDecimal cap = tenant.getAiBudgetUsd() == null ? BigDecimal.ZERO : tenant.getAiBudgetUsd();
        return loadOrEmptyUsage(tenantId)
                .flatMap(usage -> {
                    BigDecimal spent = usage.getTotalUsd() == null ? BigDecimal.ZERO : usage.getTotalUsd();
                    if (spent.compareTo(cap) >= 0) {
                        return Mono.error(new DigiPresBeException(
                                "AI budget exceeded for this tenant (spent "
                                        + spent + " of " + cap + ")",
                                1200, 429));
                    }
                    return Mono.empty();
                });
    }

    public Mono<AiUsage> record(long inputTokens, long outputTokens, BigDecimal usd) {
        return TenantContextHolder.required()
                .flatMap(ctx -> loadOrEmptyUsage(ctx.tenantId())
                        .flatMap(existing -> {
                            existing.setTotalTokensIn(existing.getTotalTokensIn() + inputTokens);
                            existing.setTotalTokensOut(existing.getTotalTokensOut() + outputTokens);
                            existing.setTotalUsd((existing.getTotalUsd() == null
                                    ? BigDecimal.ZERO : existing.getTotalUsd())
                                    .add(usd == null ? BigDecimal.ZERO : usd));
                            existing.setCallCount(existing.getCallCount() + 1);
                            return aiUsage.save(existing);
                        }));
    }

    private Mono<AiUsage> loadOrEmptyUsage(UUID tenantId) {
        String yearMonth = LocalDate.now(clockProvider.getIfAvailable(Clock::systemUTC))
                .format(YEAR_MONTH);
        return aiUsage.findByTenantIdAndYearMonth(tenantId, yearMonth)
                .defaultIfEmpty(AiUsage.builder()
                        .yearMonth(yearMonth)
                        .totalTokensIn(0L).totalTokensOut(0L)
                        .totalUsd(BigDecimal.ZERO)
                        .callCount(0L)
                        .build());
    }
}
