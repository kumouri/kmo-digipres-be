package com.kumouri.kmodigipresbe.model.responder;

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
 * E2 — one outbound responder reply, recorded so the per-tenant reply cap
 * ({@link ResponderConfig#getReplyCapPerContactPerDay()}) can be enforced per sender phone per rolling
 * day. A minimal append-only ledger (the {@code CoverageNudgeLog} posture) — kept separate so the cap is
 * honest without overloading {@link ConversationState}.
 *
 * <p>{@code TenantScoped} for isolation; <strong>not {@code Auditable}</strong> (a system ledger). The
 * compound index {@code tenant_phone_sent_idx {tenantId, phone, sentAt}} backs the rolling-window count.
 */
@Document("responder_reply_logs")
@CompoundIndex(name = "tenant_phone_sent_idx", def = "{ 'tenantId': 1, 'phone': 1, 'sentAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ReplyLogEntry implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The recipient phone in E.164 (the inbound sender we replied to). */
    private String phone;

    /** When the reply was dispatched. */
    private Instant sentAt;
}
