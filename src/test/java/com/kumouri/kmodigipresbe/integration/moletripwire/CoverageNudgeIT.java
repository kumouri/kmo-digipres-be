package com.kumouri.kmodigipresbe.integration.moletripwire;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — CoverageNudgeIT: drives the (default-OFF) coverage-window nudge job, enabled in-test, and
 * proves it (a) selects only active-coverage Projects, (b) dispatches one nudge SMS, and (c) is
 * idempotent per (project, period) — a second sweep in the same period sends ZERO duplicate. Mirrors
 * the {@code OnTheWayDispatchIT} posture: {@code TwilioSmsService} is the {@code @MockitoBean} seam
 * (its base URL is not config-driven), and the visible-for-test {@code nudgeDueOnce()} is blocked
 * deterministically.
 *
 * <h2>§7</h2>
 * The job is default-OFF in prod/CI ({@code matchIfMissing=false}); this IT explicitly opts in via
 * {@code kmosf.modules.coverage-nudge.enabled=true} <strong>and</strong> mocks the SMS seam, so no
 * live send occurs. The scheduled trigger's initial delay is pushed far out so the {@code @Scheduled}
 * tick never races the deterministic {@code nudgeDueOnce()} call.
 *
 * <p>Shard-safe: the only mock is the precedented Twilio notify seam; self-clean {@code mongo.remove}
 * {@code @BeforeEach}; no {@code application-test.properties} / {@code build.gradle} shard change.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.coverage-nudge.enabled=true",
        // Push the @Scheduled tick far out so only the explicit nudgeDueOnce() runs in-test.
        "kmosf.modules.coverage-nudge.initial-delay-ms=3600000",
        "kmosf.modules.coverage-nudge.interval-ms=3600000"
})
class CoverageNudgeIT {

    private static final String CUSTOMER_PHONE = "+16185550123";

    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired CoverageNudgeJob nudgeJob;
    @Autowired CoverageNudgeLogRepository nudgeLogs;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private UUID activeProjectId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), CoverageNudgeLog.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsTo.clear();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("coverage-nudge-it-" + tenantId)
                .displayName("Coverage Nudge IT Tenant").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        // A coverage customer Contact with a reachable phone.
        Contact customer = mongo.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ContactType.PERSON).displayName("Active Coverage Customer")
                .phones(List.of(PhoneNumber.builder().number(CUSTOMER_PHONE).label("mobile").build()))
                .build()).block();

        // Active-coverage Project — window still open (ends 30 days out).
        Project active = mongo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .code("PRJ-2026-201").name("Active Coverage")
                .status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(customer.getId())
                .coverageWindowEndsAt(Instant.now().plus(30, ChronoUnit.DAYS))
                .build()).block();
        activeProjectId = active.getId();

        // Expired-coverage Project — window closed (ended 5 days ago); must NOT be nudged.
        mongo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .code("PRJ-2026-202").name("Expired Coverage")
                .status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(customer.getId())
                .coverageWindowEndsAt(Instant.now().minus(5, ChronoUnit.DAYS))
                .build()).block();

        // No-coverage Project — coverageWindowEndsAt null; must NOT be nudged.
        mongo.save(Project.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .code("PRJ-2026-203").name("No Coverage")
                .status(Project.ProjectStatus.ACTIVE)
                .primaryContactId(customer.getId())
                .build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    @Test
    void nudgesOnlyActiveCoverageProject_once_andIsIdempotentPerPeriod() {
        // First sweep — exactly one nudge (the active-coverage Project only).
        nudgeJob.nudgeDueOnce().block();

        assertThat(smsTo).containsExactly(CUSTOMER_PHONE);

        // A ledger row exists for the active project (and only it).
        List<CoverageNudgeLog> logs = mongo.findAll(CoverageNudgeLog.class).collectList().block();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getProjectId()).isEqualTo(activeProjectId);
        assertThat(logs.get(0).getPeriodKey()).isNotBlank();
        assertThat(logs.get(0).getTenantId()).isEqualTo(tenantId);

        // The advisory event fired once.
        assertThat(observed).filteredOn(e -> DomainEventType.COVERAGE_NUDGE_SENT.equals(e.type()))
                .hasSize(1);

        // Second sweep in the same period — idempotent: ZERO additional send, still one ledger row.
        nudgeJob.nudgeDueOnce().block();

        assertThat(smsTo).containsExactly(CUSTOMER_PHONE); // still exactly one
        assertThat(mongo.findAll(CoverageNudgeLog.class).collectList().block()).hasSize(1);
    }
}
