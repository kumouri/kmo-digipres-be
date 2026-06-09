package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

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
 * T4 — one Switchboard deflection-analytics row: how a single inbound patient message was resolved
 * (logistics-answered / clinical-tripwire / staff-handoff). An append-only system ledger (the
 * {@code ReplyLogEntry} / {@code CoverageNudgeLog} posture).
 *
 * <h2>PHI-free by construction</h2>
 * This row carries <strong>only</strong> {@code tenantId}, the {@link SwitchboardDeflectionCategory}, and
 * the timestamp — <strong>no phone, no message body, no patient identity, no content of any kind</strong>.
 * It is a pure counter, so the analytics endpoint can report deflection rates without ever touching PHI.
 *
 * <p>{@code TenantScoped} for isolation; <strong>not {@code Auditable}</strong> (a system ledger). The
 * compound index {@code tenant_category_at_idx {tenantId, category, occurredAt}} backs the per-category
 * (and rolling-window) counts.
 */
@Document("switchboard_deflection_logs")
@CompoundIndex(name = "tenant_category_at_idx",
        def = "{ 'tenantId': 1, 'category': 1, 'occurredAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SwitchboardDeflectionLog implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** How the inbound message was resolved (the only "what happened" field — never content). */
    private SwitchboardDeflectionCategory category;

    /** When the message was resolved. */
    private Instant occurredAt;
}
