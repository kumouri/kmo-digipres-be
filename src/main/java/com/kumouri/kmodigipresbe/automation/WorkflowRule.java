package com.kumouri.kmodigipresbe.automation;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
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
 * Per-tenant trigger-conditions-action rule. The {@link RuleEngine} subscribes
 * to the {@link DomainEventPublisher} stream and dispatches matching events to
 * each action in order. Failure of one action does not stop the others — logged
 * as a WARN and surfaced on the rule's last-run summary.
 */
@Document("workflow_rules")
@CompoundIndex(name = "tenant_trigger_idx", def = "{ 'tenantId': 1, 'trigger': 1, 'active': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WorkflowRule implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;

    /**
     * One of {@link DomainEventType}'s constants — the only event type the rule fires on.
     * To handle multiple event types, create multiple rules.
     */
    private String trigger;

    @Builder.Default
    private List<RuleCondition> conditions = List.of();

    @Builder.Default
    private List<RuleAction> actions = List.of();

    @Builder.Default
    private boolean active = true;

    private Instant lastFiredAt;
    private long fireCount;
    private String lastErrorMessage;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
