package com.kumouri.kmodigipresbe.model.responder;

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
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * E2 — lightweight, tenant-scoped conversation state for one inbound-SMS thread, keyed
 * {@code (tenantId, phone)}, so multi-turn intents work generically without each consumer re-inventing
 * session tracking.
 *
 * <p>This is an <strong>additive</strong> {@code @Document} (design directive #2 — do NOT overload
 * {@code WaitlistOffer} / {@code ConciergeConversation}); it is the generic engine's own minimal state.
 * The shipped {@code InboundSmsService.route()} is a one-shot classifier; the responder may need a few
 * turns (e.g. "what time works?" → "2pm"), so the router refreshes this row per turn and a handler can
 * read/accumulate {@link #slots} across turns.
 *
 * <h2>TTL</h2>
 * {@link #expiresAt} carries a Mongo TTL index ({@code @Indexed(expireAfter = "0s")} over the explicit
 * expiry instant — the {@code MagicLinkToken} precedent), so stale threads self-evict. The router sets
 * {@code expiresAt = now + kmosf.responder.conversation-ttl} (default 24h) on every turn, so an active
 * thread stays alive and an abandoned one is reaped.
 *
 * <p>{@code TenantScoped} for isolation; <strong>not {@code Auditable}</strong> (an
 * automation/transcript record, the {@code ConciergeConversation} rationale). {@code @Version} is the
 * optimistic-lock backstop for two near-simultaneous inbound texts on the same thread.
 */
@Document("responder_conversations")
@CompoundIndex(name = "tenant_phone_idx", def = "{ 'tenantId': 1, 'phone': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConversationState implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The other party's phone in E.164 (the inbound {@code From}) — the per-thread correlation key. */
    private String phone;

    /** The vertical this thread belongs to (copied from {@link ResponderConfig#getVertical()}). */
    private String vertical;

    /** The most-recently classified intent on this thread (null until the first classify). */
    private String currentIntent;

    /** Accumulated structured slots across turns (e.g. {@code preferredTime}); never null. */
    @Builder.Default
    private Map<String, String> slots = new HashMap<>();

    /** How many inbound turns this thread has processed. */
    @Builder.Default
    private int turnCount = 0;

    /** When the most recent inbound text was received. */
    private Instant lastInboundAt;

    /** TTL anchor — the row self-evicts once this instant passes (refreshed each turn). */
    @Indexed(name = "responder_conversation_ttl_idx", expireAfter = "0s")
    private Instant expiresAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
