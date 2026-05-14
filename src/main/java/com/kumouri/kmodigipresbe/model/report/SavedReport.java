package com.kumouri.kmodigipresbe.model.report;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.automation.RuleCondition;
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

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A user-defined report definition. The {@code entityType} controls the underlying
 * Mongo collection ({@code "deals"} / {@code "contacts"} / {@code "activities"} for
 * the initial cut); {@code filterTree} is a list of {@link RuleCondition}s ANDed
 * together (reused from Phase 6's workflow rules and evaluated against the entity
 * via {@link com.kumouri.kmodigipresbe.automation.condition.ConditionEvaluator} —
 * promoted in 9d). {@code groupBy} are document field paths; {@code aggregations}
 * are output columns.
 *
 * <p>When {@code scheduleCron} is non-null, {@code ReportScheduler} renders the
 * report on that cron and emails it to {@code scheduleRecipients} via the Phase
 * 9c {@code TransactionalEmailService}.
 */
@Document("saved_reports")
@CompoundIndex(name = "tenant_entity_idx", def = "{ 'tenantId': 1, 'entityType': 1 }")
@CompoundIndex(name = "tenant_schedule_idx", def = "{ 'tenantId': 1, 'scheduleCron': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SavedReport implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;

    /** Underlying collection key (e.g. {@code "deals"}, {@code "contacts"}). */
    private String entityType;

    @Builder.Default
    private List<RuleCondition> filterTree = List.of();

    @Builder.Default
    private List<String> groupBy = List.of();

    @Builder.Default
    private List<AggregationSpec> aggregations = List.of();

    @Builder.Default
    private ChartHint chartHint = ChartHint.TABLE;

    /** Spring {@code cron} expression. Null = unscheduled. */
    private String scheduleCron;

    @Builder.Default
    private List<String> scheduleRecipients = List.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
