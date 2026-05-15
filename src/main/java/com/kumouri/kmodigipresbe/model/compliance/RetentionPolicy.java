package com.kumouri.kmodigipresbe.model.compliance;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.automation.RuleCondition;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Per-tenant data retention policy for a named entity type.
 *
 * <p>{@code retentionDays == 0} means keep forever. The nightly purge job
 * ({@link com.kumouri.kmodigipresbe.service.compliance.RetentionPolicyService})
 * deletes documents older than the configured window, unless they match one
 * of the {@code exceptions} conditions (evaluated against the entity's
 * {@code customFields} map so staff can tag documents for legal holds).
 */
@Document("retention_policies")
@CompoundIndex(name = "tenant_entity_idx",
        def = "{ 'tenantId': 1, 'entityType': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RetentionPolicy implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Matches document collection names — e.g. {@code "Activity"}, {@code "InboxMessage"}. */
    private String entityType;

    /** Retention window in days. {@code 0} means retain indefinitely. */
    private int retentionDays;

    /**
     * Entities whose {@code customFields} match any of these conditions are exempt
     * from purging even if they exceed the retention window.
     */
    @Builder.Default
    private List<RuleCondition> exceptions = List.of();

    @LastModifiedDate
    private Instant updatedAt;
}
