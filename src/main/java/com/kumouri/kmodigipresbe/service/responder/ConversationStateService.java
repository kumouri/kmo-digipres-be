package com.kumouri.kmodigipresbe.service.responder;

import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import com.kumouri.kmodigipresbe.repository.responder.ConversationStateRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * E2 — lightweight tenant-scoped conversation state, keyed {@code (tenantId, phone)}, with a TTL, so
 * multi-turn intents work generically. Owns find-or-create + per-turn update of {@link ConversationState}.
 *
 * <h2>§9 reactive invariant</h2>
 * find-or-create is an <strong>explicit-boolean</strong> branch
 * ({@code findByTenantIdAndPhone(...).map(Optional::of).defaultIfEmpty(empty)} → present ? load : create)
 * — <strong>never {@code switchIfEmpty(create)}</strong> (the conditional-create trap). The unique
 * {@code tenant_phone_idx} is the concurrent backstop: a near-simultaneous second inbound that loses the
 * create race is retried as a load.
 */
@Slf4j
public class ConversationStateService {

    private final ConversationStateRepository conversations;
    private final Duration ttl;

    public ConversationStateService(ConversationStateRepository conversations, Duration ttl) {
        this.conversations = conversations;
        this.ttl = ttl == null || ttl.isZero() || ttl.isNegative() ? Duration.ofHours(24) : ttl;
    }

    /**
     * Find the existing thread for {@code (tenantId, phone)} or create a fresh one — explicit-boolean,
     * never {@code switchIfEmpty(create)}. A new row starts with {@code turnCount=0} and a TTL anchor; the
     * caller bumps it via {@link #recordTurn}. Concurrent-create collisions on the unique index are
     * retried as a load (the find-or-create idempotency backstop).
     */
    public Mono<ConversationState> findOrCreate(UUID tenantId, String phone, String vertical) {
        Instant now = Instant.now();
        return conversations.findByTenantIdAndPhone(tenantId, phone)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(existing -> {
                    if (existing.isPresent()) {
                        return Mono.just(existing.get());
                    }
                    ConversationState fresh = ConversationState.builder()
                            .id(UUID.randomUUID())
                            .tenantId(tenantId)
                            .phone(phone)
                            .vertical(vertical)
                            .slots(new HashMap<>())
                            .turnCount(0)
                            .lastInboundAt(now)
                            .expiresAt(now.plus(ttl))
                            .build();
                    return conversations.save(fresh)
                            // Concurrent second-inbound lost the create race → load the winner.
                            .onErrorResume(org.springframework.dao.DuplicateKeyException.class,
                                    dk -> conversations.findByTenantIdAndPhone(tenantId, phone));
                });
    }

    /**
     * Record one processed inbound turn on {@code state}: bump {@code turnCount}, set {@code currentIntent}
     * (when classified), merge any extracted slots, refresh {@code lastInboundAt} + the TTL anchor, and
     * save. Returns the persisted row.
     */
    public Mono<ConversationState> recordTurn(ConversationState state, IntentClassification classification) {
        Instant now = Instant.now();
        Map<String, String> slots = state.getSlots() == null ? new HashMap<>() : new HashMap<>(state.getSlots());
        String currentIntent = state.getCurrentIntent();
        if (classification != null) {
            if (!classification.isUnknown()) {
                currentIntent = classification.intent();
            }
            if (classification.extractedSlots() != null) {
                slots.putAll(classification.extractedSlots());
            }
        }
        ConversationState updated = state.toBuilder()
                .currentIntent(currentIntent)
                .slots(slots)
                .turnCount(state.getTurnCount() + 1)
                .lastInboundAt(now)
                .expiresAt(now.plus(ttl))
                .build();
        return conversations.save(updated);
    }
}
