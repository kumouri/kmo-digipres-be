package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * T7 (Health "RescheduleFlow") — one row of the PHI-free fill-funnel analytics ledger: which stage of the
 * cancel → offer → claim → filled funnel happened. An append-only system ledger (the
 * {@code SwitchboardDeflectionLog} / {@code ReplyLogEntry} posture).
 *
 * <h2>PHI-free by construction</h2>
 * This row carries <strong>only</strong> {@code tenantId}, the {@link RescheduleFillEvent}, and the
 * timestamp — <strong>no phone, no patient identity, no appointment id, no message body, no content of any
 * kind, and certainly no clinical field</strong>. It is a pure counter, so the analytics endpoint can report
 * fill rates without ever touching PHI (fence F1 — there is nothing here to leak).
 *
 * <p>{@code TenantScoped} for isolation; <strong>not {@code Auditable}</strong> (a system ledger). The
 * compound index {@code tenant_event_at_idx {tenantId, event, occurredAt}} backs the per-stage counts.
 */
@Document("frontdesk_reschedule_fill_logs")
@CompoundIndex(name = "tenant_event_at_idx",
        def = "{ 'tenantId': 1, 'event': 1, 'occurredAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RescheduleFillLog implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Which funnel stage this row records (the only "what happened" field — never content). */
    private RescheduleFillEvent event;

    /** When the stage happened. */
    private Instant occurredAt;
}
