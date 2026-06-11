package com.kumouri.kmodigipresbe.service.ai;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.ai.AiUsage;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.AiUsageRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies AiUsageRecorder's pre-call budget check and post-call recording.
 * Default budget is $0 — AI is opt-in only per the Phase 9f decision.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class AiBudgetGateIT {

    @Autowired AiUsageRecorder recorder;
    @Autowired AiUsageRepository aiUsage;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), AiUsage.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
    }

    @Test
    void zeroBudget_blocksCall_errorCode1200() {
        tenants.save(Tenant.builder()
                        .id(tenantId).slug("zero-budget-" + tenantId).displayName("Zero")
                        .status(Tenant.TenantStatus.ACTIVE)
                        .aiBudgetUsd(BigDecimal.ZERO)
                        .build())
                .block();

        StepVerifier.create(recorder.checkBudget()
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1200);
                    assertThat(((DigiPresBeException) err).getHttpStatusCode()).isEqualTo(429);
                })
                .verify();
    }

    @Test
    void budgetWithRemainingHeadroom_passes() {
        tenants.save(Tenant.builder()
                        .id(tenantId).slug("budget-" + tenantId).displayName("Budget")
                        .status(Tenant.TenantStatus.ACTIVE)
                        .aiBudgetUsd(new BigDecimal("5.00"))
                        .build())
                .block();

        StepVerifier.create(recorder.checkBudget()
                        .contextWrite(TenantContextHolder.write(ctx)))
                .verifyComplete();
    }

    @Test
    void recordIncrementsCounts_andSubsequentCheckBlocksWhenOverCap() {
        tenants.save(Tenant.builder()
                        .id(tenantId).slug("cap-" + tenantId).displayName("Cap")
                        .status(Tenant.TenantStatus.ACTIVE)
                        .aiBudgetUsd(new BigDecimal("1.00"))
                        .build())
                .block();

        // First record: 0.40 USD spent. Still under 1.00 cap.
        recorder.record(1000L, 500L, new BigDecimal("0.40"))
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        StepVerifier.create(recorder.checkBudget()
                        .contextWrite(TenantContextHolder.write(ctx)))
                .verifyComplete();

        // Second record: 0.70 USD more = 1.10 total. Over cap.
        recorder.record(2000L, 1000L, new BigDecimal("0.70"))
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        StepVerifier.create(recorder.checkBudget()
                        .contextWrite(TenantContextHolder.write(ctx)))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    assertThat(((DigiPresBeException) err).getErrorCode()).isEqualTo(1200);
                })
                .verify();

        // Counts persisted under the current yearMonth.
        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        AiUsage row = aiUsage.findByTenantIdAndYearMonth(tenantId, ym)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(row).isNotNull();
        assertThat(row.getTotalTokensIn()).isEqualTo(3000L);
        assertThat(row.getTotalTokensOut()).isEqualTo(1500L);
        assertThat(row.getCallCount()).isEqualTo(2L);
        assertThat(row.getTotalUsd()).isEqualByComparingTo(new BigDecimal("1.10"));
    }

    /**
     * AI-06 lost-write robustness: many concurrent record() calls all race the SAME (tenant, yearMonth)
     * @Version row. Pre-fix, the optimistic-lock losers threw OptimisticLockingFailureException — and since
     * callers swallow record() failures, that spend silently vanished (cap erosion). With the bounded
     * retryWhen, EVERY call's spend must land: the persisted totals equal the exact sum of all calls, and
     * the call count equals N. This is the regression that proves no spend is dropped under contention.
     */
    @Test
    void concurrentRecords_noLostWrites_allSpendCounted() {
        tenants.save(Tenant.builder()
                        .id(tenantId).slug("concurrent-" + tenantId).displayName("Concurrent")
                        .status(Tenant.TenantStatus.ACTIVE)
                        .aiBudgetUsd(new BigDecimal("100.00"))
                        .build())
                .block();

        // 8-way contention: enough to exercise BOTH races for real (the first-of-month insert stampede on
        // the unique tenant_month_idx, then the @Version update race as the row fills) while staying within
        // the bounded retry budget — mirroring a realistic concurrent AI burst for one tenant.
        int n = 8;
        BigDecimal perCall = new BigDecimal("0.10");

        // Fan out N record() calls in parallel on the elastic scheduler so they genuinely contend on the
        // single ai_usage row (each is its own subscription with the tenant context written in).
        Long completed = Flux.range(0, n)
                .flatMap(i -> recorder.record(10L, 5L, perCall)
                                .contextWrite(TenantContextHolder.write(ctx))
                                .subscribeOn(Schedulers.boundedElastic()),
                        /* concurrency */ n)
                .count()
                .block();
        assertThat(completed).isEqualTo((long) n);

        String ym = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"));
        AiUsage row = aiUsage.findByTenantIdAndYearMonth(tenantId, ym)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(row).isNotNull();
        // No lost writes: every one of the N increments composed onto the row.
        assertThat(row.getCallCount()).isEqualTo((long) n);
        assertThat(row.getTotalTokensIn()).isEqualTo(10L * n);
        assertThat(row.getTotalTokensOut()).isEqualTo(5L * n);
        assertThat(row.getTotalUsd()).isEqualByComparingTo(perCall.multiply(BigDecimal.valueOf(n)));
    }
}
