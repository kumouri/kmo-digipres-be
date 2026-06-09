package com.kumouri.kmodigipresbe.module.responder;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import com.kumouri.kmodigipresbe.service.responder.ConversationStateService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2 — ConversationStateIT: multi-turn state keyed (tenantId, phone). Proves find-or-create (first turn
 * creates, second finds the SAME row), per-turn slot-merge + turnCount bump + TTL refresh, and
 * cross-tenant isolation. Self-clean {@code mongo.remove}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ConversationStateIT {

    @Autowired ConversationStateService service;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private static final String PHONE = "+16185550200";

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), ConversationState.class).block();
        tenantId = UUID.randomUUID();
    }

    @Test
    void firstTurn_creates_secondTurn_findsSameRow_mergesSlots() {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));

        ConversationState first = service.findOrCreate(tenantId, PHONE, "home")
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(first).isNotNull();
        assertThat(first.getTurnCount()).isEqualTo(0);
        assertThat(first.getExpiresAt()).isNotNull();

        ConversationState afterTurn1 = service.recordTurn(first, new IntentClassification(
                        "SCHEDULE_VISIT", 0.8, Map.of("preferredTime", "Tue 2pm")))
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(afterTurn1.getTurnCount()).isEqualTo(1);
        assertThat(afterTurn1.getCurrentIntent()).isEqualTo("SCHEDULE_VISIT");
        assertThat(afterTurn1.getSlots()).containsEntry("preferredTime", "Tue 2pm");

        // Second inbound for the same (tenant, phone) → the SAME row (find, not a second create).
        ConversationState second = service.findOrCreate(tenantId, PHONE, "home")
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(second.getId()).isEqualTo(first.getId());
        assertThat(second.getTurnCount()).isEqualTo(1);

        ConversationState afterTurn2 = service.recordTurn(second, new IntentClassification(
                        "SCHEDULE_VISIT", 0.9, Map.of("address", "123 Main")))
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(afterTurn2.getTurnCount()).isEqualTo(2);
        // Slots accumulate across turns.
        assertThat(afterTurn2.getSlots()).containsEntry("preferredTime", "Tue 2pm");
        assertThat(afterTurn2.getSlots()).containsEntry("address", "123 Main");

        // Exactly ONE row for (tenant, phone).
        assertThat(mongo.findAll(ConversationState.class).collectList().block()).hasSize(1);
    }

    @Test
    void crossTenantIsolation_separateRows() {
        UUID tenantA = UUID.randomUUID();
        UUID tenantB = UUID.randomUUID();
        TenantContext ctxA = new TenantContext(tenantA, null, Set.of("INTEGRATION_TWILIO"));
        TenantContext ctxB = new TenantContext(tenantB, null, Set.of("INTEGRATION_TWILIO"));

        ConversationState a = service.findOrCreate(tenantA, PHONE, "home")
                .contextWrite(TenantContextHolder.write(ctxA)).block();
        ConversationState b = service.findOrCreate(tenantB, PHONE, "health")
                .contextWrite(TenantContextHolder.write(ctxB)).block();

        assertThat(a.getId()).isNotEqualTo(b.getId());
        assertThat(a.getTenantId()).isEqualTo(tenantA);
        assertThat(b.getTenantId()).isEqualTo(tenantB);
        assertThat(b.getVertical()).isEqualTo("health");
        assertThat(mongo.findAll(ConversationState.class).collectList().block()).hasSize(2);
    }

    @Test
    void unknownClassification_doesNotOverwriteCurrentIntent() {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        ConversationState s = service.findOrCreate(tenantId, PHONE, "home")
                .contextWrite(TenantContextHolder.write(ctx)).block();
        s = service.recordTurn(s, new IntentClassification("PRICING_QUESTION", 0.8, Map.of()))
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(s.getCurrentIntent()).isEqualTo("PRICING_QUESTION");

        // An UNKNOWN turn bumps the count but keeps the last known intent.
        s = service.recordTurn(s, IntentClassification.unknown())
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(s.getTurnCount()).isEqualTo(2);
        assertThat(s.getCurrentIntent()).isEqualTo("PRICING_QUESTION");
    }
}
