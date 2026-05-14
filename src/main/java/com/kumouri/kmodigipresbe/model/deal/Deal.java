package com.kumouri.kmodigipresbe.model.deal;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Document("deals")
@CompoundIndex(name = "tenant_stage_idx", def = "{ 'tenantId': 1, 'stage': 1 }")
@CompoundIndex(name = "tenant_owner_idx", def = "{ 'tenantId': 1, 'ownerId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Deal implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private String title;

    @Builder.Default
    private PipelineStage stage = PipelineStage.NEW;

    private BigDecimal value;

    @Builder.Default
    private String currency = "USD";

    private LocalDate expectedCloseDate;

    private UUID primaryContactId;
    private UUID companyId;
    private UUID ownerId;

    /**
     * Required when {@code stage == LOST}; null otherwise.
     */
    private String lostReason;

    private Instant stageChangedAt;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
