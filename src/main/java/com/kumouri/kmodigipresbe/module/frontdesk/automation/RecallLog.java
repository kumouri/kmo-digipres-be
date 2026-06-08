package com.kumouri.kmodigipresbe.module.frontdesk.automation;

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
import java.util.UUID;

/**
 * FrontDesk IQ (FD-2) — the recall/recare re-engagement idempotency ledger. One row per
 * (tenant, contact, periodKey): the {@code RecallDetectorJob} computes the current {@code periodKey}
 * (ISO week), runs an explicit-boolean probe, then inserts this row <strong>FIRST</strong> (unique
 * {@code tenant_contact_period_idx}, with {@code onErrorResume(DuplicateKeyException → empty)} as the
 * concurrent-re-run backstop) and only then enrolls the lapsed contact into the recall Sequence / sends
 * the generic recare nudge — exactly the {@code CoverageNudgeLog} ledger-insert-FIRST pattern. So a
 * restart / a second sweep within the same period sends ZERO duplicate nudge and creates ZERO duplicate
 * enrollment for the same contact.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the {@code CoverageNudgeLog} rationale: an
 * infrastructure dedupe record, not a CRM entity).
 */
@Document("frontdesk_recall_logs")
@CompoundIndex(
        name = "tenant_contact_period_idx",
        def = "{'tenantId':1,'contactId':1,'periodKey':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RecallLog implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The lapsed contact this recall action was taken for; part of the unique (exactly-once) key. */
    private UUID contactId;

    /** The recall period bucket (ISO week) this row dedupes; part of the unique key. */
    private String periodKey;

    /** Whether the contact was enrolled into a recall Sequence as part of this action (for audit). */
    private boolean enrolled;

    /** Whether a generic recare nudge SMS was sent as part of this action (for audit). */
    private boolean nudged;

    private Instant sentAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
