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
 * FrontDesk IQ (FD-2) — the risk-tiered-confirmation idempotency + TCPA frequency-cap ledger. One row per
 * (tenant, appointment): the {@code FrontDeskConfirmationService} inserts this row <strong>FIRST</strong>
 * (before sending any confirmation / reminder SMS), so a re-fired {@code APPOINTMENT_RISK_SCORED} for the
 * same appointment (a nightly re-score, a restart, a concurrent emit) loses on a
 * {@code DuplicateKeyException} and produces ZERO duplicate SMS — the money-grade ledger-insert-FIRST
 * pattern, here applied to a (non-money) SMS. The direct analogue of the chairfill CF-2
 * {@link com.kumouri.kmodigipresbe.module.chairfill.automation.ReminderLog}, <strong>minus the
 * {@code depositRequired} field</strong> (health does not deposit — FrontDesk IQ plan D2 / FD-2).
 *
 * <p>{@code contactId} + {@code sentAt} additionally back the <strong>per-contact frequency cap</strong>
 * (plan §5 TCPA mitigation): before acting on an appointment the service counts this contact's rows in the
 * recent rolling window and skips if the cap is reached, so a patient with several upcoming appointments is
 * never spammed.
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the {@code ReminderLog} /
 * {@code CoverageNudgeLog} rationale: an infrastructure dedupe record, not a CRM entity).
 */
@Document("frontdesk_confirmation_logs")
@CompoundIndex(
        name = "tenant_appointment_idx",
        def = "{'tenantId':1,'appointmentId':1}",
        unique = true)
@CompoundIndex(
        name = "tenant_contact_sent_idx",
        def = "{'tenantId':1,'contactId':1,'sentAt':-1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConfirmationLog implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The appointment this confirmation action was taken for; part of the unique (exactly-once) key. */
    private UUID appointmentId;

    /** The Contact the confirmation/reminder was sent to (nullable); backs the frequency cap. */
    private UUID contactId;

    /** The risk tier that drove the action (HIGH / MEDIUM / LOW) — for audit/debug, not the key. */
    private String riskTier;

    /** Whether this was an extra confirmation ask (HIGH path, {@code true}) or a light reminder ({@code false}). */
    private boolean confirmation;

    /** Whether the copy came from Claude ({@code true}) or the generic fallback ({@code false}). */
    private boolean personalized;

    private Instant sentAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
