package com.kumouri.kmodigipresbe.module.chairfill.automation;

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
 * ChairFill CF-2 — the risk-tiered-prevention idempotency + TCPA frequency-cap ledger. One row per
 * (tenant, booking): the {@code RiskTieredPreventionService} inserts this row <strong>FIRST</strong>
 * (before sending any reminder / requiring any deposit), so a re-fired {@code BOOKING_RISK_SCORED}
 * for the same booking (a nightly re-score, a restart, a concurrent emit) loses on a
 * {@code DuplicateKeyException} and produces ZERO duplicate SMS / deposit — the money-grade
 * ledger-insert-FIRST pattern, here applied to {@code CoverageNudgeLog}'s SMS posture.
 *
 * <p>{@code contactId} + {@code sentAt} additionally back the <strong>per-contact frequency cap</strong>
 * (plan §4 TCPA mitigation): before acting on a booking the service counts this contact's rows in the
 * recent rolling window and skips if the cap is reached, so a HIGH-risk client with several upcoming
 * bookings is never spammed.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the {@code CoverageNudgeLog} /
 * {@code RecurringInvoiceOccurrence} rationale: an infrastructure dedupe record, not a CRM entity).
 */
@Document("chairfill_reminder_logs")
@CompoundIndex(
        name = "tenant_booking_idx",
        def = "{'tenantId':1,'bookingId':1}",
        unique = true)
@CompoundIndex(
        name = "tenant_contact_sent_idx",
        def = "{'tenantId':1,'contactId':1,'sentAt':-1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ReminderLog implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The booking this prevention action was taken for; part of the unique (exactly-once) key. */
    private UUID bookingId;

    /** The Contact the reminder/confirmation was sent to (nullable); backs the frequency cap. */
    private UUID contactId;

    /** The risk tier that drove the action (HIGH / MEDIUM / LOW) — for audit/debug, not the key. */
    private String riskTier;

    /** Whether a deposit was required as part of this action (HIGH path). */
    private boolean depositRequired;

    /** Whether the reminder copy came from Claude ({@code true}) or the generic fallback ({@code false}). */
    private boolean personalized;

    private Instant sentAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
