package com.kumouri.kmodigipresbe.integration.moletripwire;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Coverage-window check-in nudge job (Phase 3 — NMM coverage-window automation, piece B). Periodically
 * nudges active-coverage customers — "seeing fresh mounds? reply with a photo via your link" — over
 * SMS. Modeled on {@code OnTheWaySmsAutomation} (a visible-for-test reactive entry the test drives
 * deterministically) + the {@code ImapInboundPoller} default-OFF {@code @Scheduled} posture.
 *
 * <h2>MINIMAL + default-OFF (the briefing's bounding rules)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.coverage-nudge", name="enabled",
 * matchIfMissing=false)} — <strong>default-OFF</strong> (the {@code ImapInboundPoller} precedent): the
 * job bean is not even created in CI / any default run, so there are <strong>no live nudge sends</strong>
 * unless a deployment explicitly opts in. This keeps §7 satisfied by construction. The selector is a
 * single additive nullable field ({@code Project.coverageWindowEndsAt}) — the Project core is otherwise
 * untouched; this does NOT invent a coverage model.
 *
 * <h2>Idempotent per (project, period) — explicit-boolean, never {@code switchIfEmpty(send)}</h2>
 * For each active-coverage Project the job computes the current {@code periodKey} (ISO week), then runs
 * an <strong>explicit-boolean</strong> ledger probe
 * ({@code findBy…PeriodKey(...).map(e->true).defaultIfEmpty(false)}); only if not-yet-seen does it
 * <strong>insert the {@link CoverageNudgeLog} row FIRST</strong> (unique {@code tenant_project_period_idx},
 * with {@code onErrorResume(DuplicateKeyException → empty)} as the concurrent-re-run backstop) and then
 * send the nudge — exactly the money-grade {@code RecurringInvoiceOccurrence} ledger-insert-FIRST pattern,
 * here for a (non-money) SMS. So a restart / a second tick within the same week sends ZERO duplicate
 * nudge. <strong>Never</strong> {@code switchIfEmpty(send)} (the §9 #2 trap).
 *
 * <h2>§9 reactive + blocking-I/O</h2>
 * The {@code @Scheduled} method runs on Spring's scheduler thread pool (never the Netty event loop) and
 * subscribes the reactive chain there; the visible-for-test {@link #nudgeDueOnce()} returns a
 * {@code Mono<Void>} the IT blocks. The only {@code switchIfEmpty}-style construct is the explicit
 * not-seen boolean default — no {@code switchIfEmpty(create/send)} anywhere.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kmosf.modules.coverage-nudge", name = "enabled",
        matchIfMissing = false)
public class CoverageNudgeJob {

    private static final DateTimeFormatter ISO_WEEK =
            DateTimeFormatter.ofPattern("YYYY-'W'ww");

    private final TenantRepository tenants;
    private final ProjectRepository projects;
    private final ContactRepository contacts;
    private final CoverageNudgeLogRepository nudgeLogs;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    private final Clock clock;
    private final String nudgeMessage;

    public CoverageNudgeJob(
            TenantRepository tenants,
            ProjectRepository projects,
            ContactRepository contacts,
            CoverageNudgeLogRepository nudgeLogs,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.modules.coverage-nudge.message:"
                    + "Checking in from No Mo Mole — seeing fresh mounds in your yard? "
                    + "Reply with a photo via your link and we'll take a look.}") String nudgeMessage) {
        this.tenants = tenants;
        this.projects = projects;
        this.contacts = contacts;
        this.nudgeLogs = nudgeLogs;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.clock = Clock.systemUTC();
        this.nudgeMessage = nudgeMessage;
    }

    /**
     * Scheduled tick — fixed delay, default 24h ({@code kmosf.modules.coverage-nudge.interval-ms}).
     * Fire-and-forget subscribe on the scheduler thread (never the Netty loop); the per-tenant /
     * per-project pipeline catches and logs so one failure never aborts the rest.
     */
    @Scheduled(
            fixedDelayString = "${kmosf.modules.coverage-nudge.interval-ms:86400000}",
            initialDelayString = "${kmosf.modules.coverage-nudge.initial-delay-ms:60000}")
    public void scheduledTick() {
        nudgeDueOnce().subscribe(
                ignored -> {},
                err -> log.error("CoverageNudgeJob tick failed", err));
    }

    /**
     * Visible-for-test entry — runs one full nudge sweep across all tenants and returns when done, so
     * an IT can drive it deterministically (the {@code OnTheWaySmsAutomation.seedAll()} pattern).
     */
    public Mono<Void> nudgeDueOnce() {
        String periodKey = currentPeriodKey();
        return tenants.findAll()
                .concatMap(t -> nudgeTenant(t.getId(), periodKey)
                        .onErrorResume(err -> {
                            log.warn("Coverage-nudge sweep failed for tenant {}: {}",
                                    t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> nudgeTenant(UUID tenantId, String periodKey) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_COVERAGE_NUDGE"));
        Instant now = clock.instant();
        return projects.findAllByTenantIdAndCoverageWindowEndsAtAfter(tenantId, now)
                .concatMap(project -> nudgeProject(tenantId, project, periodKey)
                        .onErrorResume(err -> {
                            log.warn("Coverage-nudge failed for project {} (tenant {}): {}",
                                    project.getId(), tenantId, err.toString());
                            return Mono.empty();
                        }))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /**
     * Nudge a single active-coverage Project for {@code periodKey}, idempotent: explicit-boolean
     * probe → ledger-insert-FIRST → send. Never {@code switchIfEmpty(send)}.
     */
    private Mono<Void> nudgeProject(UUID tenantId, Project project, String periodKey) {
        return nudgeLogs.findByTenantIdAndProjectIdAndPeriodKey(tenantId, project.getId(), periodKey)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(alreadyNudged -> {
                    if (alreadyNudged) {
                        log.debug("Coverage nudge already sent for project {} period {} — skipping",
                                project.getId(), periodKey);
                        return Mono.empty();
                    }
                    return resolvePhone(tenantId, project)
                            .flatMap(phone -> insertLedgerThenSend(tenantId, project, periodKey, phone));
                });
    }

    /**
     * Ledger-insert-FIRST (the unique-index exactly-once backstop), then send the nudge SMS + emit
     * {@code COVERAGE_NUDGE_SENT}. A concurrent re-run loses the insert on a
     * {@code DuplicateKeyException} → {@code Mono.empty()} = zero duplicate send.
     */
    private Mono<Void> insertLedgerThenSend(UUID tenantId, Project project, String periodKey,
                                            String phone) {
        UUID contactId = project.getPrimaryContactId();
        CoverageNudgeLog log0 = CoverageNudgeLog.builder()
                .tenantId(tenantId)
                .projectId(project.getId())
                .periodKey(periodKey)
                .contactId(contactId)
                .sentAt(clock.instant())
                .build();
        return nudgeLogs.save(log0)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("Coverage nudge concurrent-fire lost ledger insert for project {} "
                            + "period {} — zero duplicate send", project.getId(), periodKey);
                    return Mono.empty();
                })
                .flatMap(saved -> sendNudge(phone)
                        .then(Mono.fromRunnable(() ->
                                emitNudgeSent(tenantId, project.getId(), periodKey, contactId))));
    }

    private Mono<Boolean> sendNudge(String phone) {
        SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                .to(new PhoneContact(phone))
                .body(nudgeMessage)
                .build();
        return twilioSmsService.sendSms(smsReq);
    }

    /**
     * Resolves the coverage customer's phone from the Project's {@code primaryContactId} →
     * {@code Contact.phones[0].number}. A Project with no primary contact or no phone is skipped
     * (returns empty — no ledger row, no nudge), so a re-run can pick it up once a phone is added.
     */
    private Mono<String> resolvePhone(UUID tenantId, Project project) {
        if (project.getPrimaryContactId() == null) {
            return Mono.empty();
        }
        return contacts.findByTenantIdAndId(tenantId, project.getPrimaryContactId())
                .mapNotNull(this::firstPhone)
                .filter(p -> !p.isBlank());
    }

    private String firstPhone(Contact contact) {
        List<PhoneNumber> phones = contact.getPhones();
        if (phones == null || phones.isEmpty()) {
            return null;
        }
        for (PhoneNumber p : phones) {
            if (p != null && p.number() != null && !p.number().isBlank()) {
                return p.number();
            }
        }
        return null;
    }

    private void emitNudgeSent(UUID tenantId, UUID projectId, String periodKey, UUID contactId) {
        java.util.Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("projectId", projectId.toString());
        payload.put("periodKey", periodKey);
        if (contactId != null) payload.put("contactId", contactId.toString());
        events.publish(DomainEvent.of(
                DomainEventType.COVERAGE_NUDGE_SENT, tenantId, projectId, payload));
    }

    /** The current nudge period bucket — ISO week, UTC (a stable canonical idempotency key). */
    private String currentPeriodKey() {
        return ISO_WEEK.format(clock.instant().atZone(ZoneOffset.UTC));
    }
}
