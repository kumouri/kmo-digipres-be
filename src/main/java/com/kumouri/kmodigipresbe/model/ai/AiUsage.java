package com.kumouri.kmodigipresbe.model.ai;

import com.kumouri.kmodigipresbe.audit.Auditable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Per-tenant per-yearMonth AI spend tally. {@code AiUsageRecorder} bumps the
 * counters after every successful AI call; {@code Tenant.aiBudgetUsd} is checked
 * against {@code totalUsd} before each call.
 */
@Document("ai_usage")
@CompoundIndex(name = "tenant_month_idx",
        def = "{ 'tenantId': 1, 'yearMonth': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class AiUsage implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** {@code YYYY-MM}. */
    private String yearMonth;

    @Builder.Default
    private long totalTokensIn = 0L;

    @Builder.Default
    private long totalTokensOut = 0L;

    @Builder.Default
    private BigDecimal totalUsd = BigDecimal.ZERO;

    @Builder.Default
    private long callCount = 0L;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
