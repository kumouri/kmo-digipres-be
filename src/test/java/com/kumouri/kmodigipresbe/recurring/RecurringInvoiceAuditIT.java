package com.kumouri.kmodigipresbe.recurring;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.audit.AuditEvent;
import com.kumouri.kmodigipresbe.audit.AuditEventRepository;
import com.kumouri.kmodigipresbe.audit.AuditOp;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoice;
import com.kumouri.kmodigipresbe.model.recurring.RecurringInvoiceOccurrence;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AC-E8 (audit half) — {@code RecurringInvoice} is {@code Auditable} (a CREATE
 * {@code audit_events} row appears on create); {@code RecurringInvoiceOccurrence}
 * is a system ledger and is <strong>NOT</strong> {@code Auditable} (NO audit row
 * — same rationale as {@code IdempotencyKey} / {@code StripeWebhookEvent}).
 *
 * <p>The Quartz-store half of AC-E8 (E-D5 RAM-fallback decision) is asserted by
 * {@code QuartzMongoJobStoreIT}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class RecurringInvoiceAuditIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired AuditEventRepository auditEvents;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), AuditEvent.class).block();
        mongo.remove(new Query(), RecurringInvoice.class).block();
        mongo.remove(new Query(), RecurringInvoiceOccurrence.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("ri-audit-" + tenantId)
                .displayName("RI Audit Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
        User user = User.builder().id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@riaudit.test").roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build();
        users.save(user).block();
        token = "Bearer " + jwt.mint(user);
    }

    @Test
    void createRecurringInvoice_writesCreateAuditEvent_butOccurrenceIsNotAudited() {
        Instant seedAt = Instant.now().plus(1, ChronoUnit.DAYS);
        RecurringInvoice created = web.post().uri("/recurring-invoices")
                .header("Authorization", token)
                .bodyValue(RecurringInvoice.builder()
                        .templateName("Audited Template")
                        .rrule("FREQ=MONTHLY;BYMONTHDAY=15")
                        .seedAt(seedAt)
                        .lineItems(List.of(LineItem.builder()
                                .description("Service")
                                .quantity(BigDecimal.ONE)
                                .unitPrice(new BigDecimal("300.00"))
                                .discountPercent(BigDecimal.ZERO)
                                .taxPercent(BigDecimal.ZERO)
                                .build()))
                        .build())
                .exchange()
                .expectStatus().isCreated()
                .expectBody(RecurringInvoice.class).returnResult().getResponseBody();

        assertThat(created).isNotNull();

        // RecurringInvoice IS Auditable -> a CREATE audit event exists.
        List<AuditEvent> events = auditEvents
                .findAllByTenantIdAndEntityTypeAndEntityIdOrderByAtDesc(
                        tenantId, "RecurringInvoice", created.getId())
                .collectList().block();
        assertThat(events).isNotEmpty();
        assertThat(events.get(0).getOp()).isEqualTo(AuditOp.CREATE);

        // RecurringInvoiceOccurrence is a system ledger (NOT Auditable) -> there
        // must be ZERO audit_events whose entityType is RecurringInvoiceOccurrence.
        List<AuditEvent> all = mongo.findAll(AuditEvent.class).collectList().block();
        assertThat(all).noneMatch(e ->
                "RecurringInvoiceOccurrence".equals(e.getEntityType()));
    }
}
