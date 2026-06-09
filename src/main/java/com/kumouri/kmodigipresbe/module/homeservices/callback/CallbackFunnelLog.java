package com.kumouri.kmodigipresbe.module.homeservices.callback;

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
 * T5 (Home Services "Instant Callback") — one row in the missed-call → callback <strong>recovery
 * funnel</strong> analytics ledger: a single {@link CallbackFunnelStage} transition. An append-only
 * counter ledger (the {@code SwitchboardDeflectionLog} posture).
 *
 * <p>Carries only {@code tenantId}, the {@link CallbackFunnelStage}, an optional {@code callSid}
 * correlation, and the timestamp — a pure counter so the recovery-stats endpoint can report the funnel
 * without joining the heavier {@link CallbackRequest}. {@code TenantScoped} for isolation;
 * <strong>not {@code Auditable}</strong> (a system ledger).
 */
@Document("callback_funnel_logs")
@CompoundIndex(name = "tenant_stage_at_idx", def = "{ 'tenantId': 1, 'stage': 1, 'occurredAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CallbackFunnelLog implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The funnel stage this row records (offered / accepted / dispatched). */
    private CallbackFunnelStage stage;

    /** The originating voicemail CallSid, when known (nullable — a reply with no prior voicemail). */
    private String callSid;

    /** When the transition happened. */
    private Instant occurredAt;
}
