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
}
