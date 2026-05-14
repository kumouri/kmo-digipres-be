package com.kumouri.kmodigipresbe.service.sequence;

import com.kumouri.kmodigipresbe.automation.condition.ConditionEvaluator;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.model.sequence.SequenceEnrollment;
import com.kumouri.kmodigipresbe.model.sequence.SequenceStep;
import com.kumouri.kmodigipresbe.repository.EmailEngagementRepository;
import com.kumouri.kmodigipresbe.repository.SequenceEnrollmentRepository;
import com.kumouri.kmodigipresbe.repository.SequenceRepository;
import com.kumouri.kmodigipresbe.service.communication.TransactionalEmailService;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendRequest;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.scheduling.annotation.Scheduled;
// Phase 9d: SequenceEngine reads enrollment.contactEmail (denormalised at enroll
// time by SequenceCrudService) rather than re-loading the Contact entity.
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Drives every ACTIVE {@link SequenceEnrollment} forward through its
 * {@link Sequence}. A fixed-rate Spring {@code @Scheduled} tick polls due
 * enrollments (status=ACTIVE, nextFireAt &le; now) and processes one step per
 * enrollment per tick.
 *
 * <h2>Step handling</h2>
 * <ul>
 *   <li>{@code EMAIL_SEND}: render request, send via
 *       {@link TransactionalEmailService}, store the returned MessageID on
 *       {@code enrollment.lastMessageId}, append the step index to
 *       {@code completedSteps}, advance.</li>
 *   <li>{@code WAIT}: on first encounter, set {@code nextFireAt = now + waitDuration}
 *       and skip. On subsequent ticks (where now &ge; nextFireAt), mark completed and
 *       advance. {@code waitDuration} parses via {@link Duration#parse}.</li>
 *   <li>{@code BRANCH}: look up the most recent {@link EmailEngagement} for
 *       {@code lastMessageId}, build a payload {@code {event, recipient}}, evaluate
 *       {@code branchCondition} with {@link ConditionEvaluator}, advance to
 *       {@code branchTrueNextStep} or {@code branchFalseNextStep}.</li>
 *   <li>{@code EXIT}: mark enrollment {@code COMPLETED}.</li>
 *   <li>Running past the last step also marks {@code COMPLETED}.</li>
 * </ul>
 *
 * <h2>Idempotency</h2>
 * Before firing a step, the engine checks {@code completedSteps.contains(idx)} and
 * silently advances if so. A duplicate tick (Quartz misfire, restart mid-step)
 * does not produce a duplicate send.
 *
 * <h2>Tenant context</h2>
 * The tick runs without any tenant context. The {@code findDue} query uses
 * {@link ReactiveMongoTemplate} directly so it sees enrollments across tenants.
 * Each enrollment is then processed under a synthetic
 * {@code TenantContext(tenantId, null, ["SEQUENCE_ENGINE"])} so saves stamp
 * correctly, audit events attribute correctly, and
 * {@code TransactionalEmailService} can resolve the per-tenant Postmark token.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SequenceEngine {

    public static final String SYSTEM_ROLE = "SEQUENCE_ENGINE";

    private final ReactiveMongoTemplate mongo;
    private final SequenceRepository sequences;
    private final SequenceEnrollmentRepository enrollments;
    private final EmailEngagementRepository engagements;
    private final TransactionalEmailService transactionalEmail;
    private final org.springframework.beans.factory.ObjectProvider<Clock> clockProvider;

    @Value("${kmosf.sequence.default-from:no-reply@kmosolutionsfoundry.com}")
    private String defaultFrom;

    /**
     * Polls every minute by default. Initial-delay avoids racing with context startup.
     * Override interval with {@code kmosf.sequence.tick-ms} for tests.
     */
    @Scheduled(
            fixedRateString = "${kmosf.sequence.tick-ms:60000}",
            initialDelayString = "${kmosf.sequence.initial-delay-ms:30000}")
    public void tick() {
        runDueOnce()
                .onErrorContinue((err, evt) ->
                        log.warn("SequenceEngine tick dropped enrollment {}: {}", evt, err.toString()))
                .subscribe();
    }

    /**
     * Scans + processes one round of due enrollments. Test code calls this
     * directly with an injected fixed Clock to fast-forward.
     */
    public Mono<Void> runDueOnce() {
        Instant now = now();
        Query q = new Query(new Criteria().andOperator(
                Criteria.where("status").is(SequenceEnrollment.Status.ACTIVE.name()),
                new Criteria().orOperator(
                        Criteria.where("nextFireAt").is(null),
                        Criteria.where("nextFireAt").lte(now))));
        return mongo.find(q, SequenceEnrollment.class)
                .flatMap(this::processEnrollment)
                .onErrorContinue((err, evt) ->
                        log.warn("Sequence enrollment {} failed: {}", evt, err.toString()))
                .then();
    }

    Mono<Void> processEnrollment(SequenceEnrollment enrollment) {
        TenantContext ctx = new TenantContext(
                enrollment.getTenantId(), null, Set.of(SYSTEM_ROLE));
        return sequences.findById(enrollment.getSequenceId())
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("Enrollment {} references missing sequence {} — exiting",
                            enrollment.getId(), enrollment.getSequenceId());
                    enrollment.setStatus(SequenceEnrollment.Status.EXITED);
                    return enrollments.save(enrollment).then(Mono.empty());
                }))
                .flatMap(seq -> processStep(seq, enrollment))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Void> processStep(Sequence seq, SequenceEnrollment enrollment) {
        List<SequenceStep> steps = seq.getSteps() == null ? List.of() : seq.getSteps();
        int idx = enrollment.getCurrentStepIndex();
        if (idx >= steps.size()) {
            return complete(enrollment);
        }
        SequenceStep step = steps.get(idx);
        if (enrollment.getCompletedSteps() != null
                && enrollment.getCompletedSteps().contains(step.getStepIndex())) {
            return advance(enrollment, idx + 1);
        }
        return switch (step.getType()) {
            case EMAIL_SEND -> handleEmailSend(step, enrollment);
            case WAIT -> handleWait(step, enrollment);
            case BRANCH -> handleBranch(step, enrollment);
            case EXIT -> complete(enrollment);
        };
    }

    private Mono<Void> handleEmailSend(SequenceStep step, SequenceEnrollment enrollment) {
        String toAddress = enrollment.getContactEmail();
        if (toAddress == null || toAddress.isBlank()) {
            log.warn("Enrollment {} contact {} has no contactEmail — exiting sequence",
                    enrollment.getId(), enrollment.getContactId());
            enrollment.setStatus(SequenceEnrollment.Status.EXITED);
            return enrollments.save(enrollment).then();
        }
        TransactionalSendRequest req = new TransactionalSendRequest(
                List.of(toAddress),
                step.getEmailFrom() != null ? step.getEmailFrom() : defaultFrom,
                step.getEmailSubject(),
                step.getEmailHtmlBody(),
                step.getEmailTextBody(),
                step.getEmailTag(),
                Map.of("kmosf_contact_id", enrollment.getContactId().toString()));
        return transactionalEmail.send(req)
                .doOnNext(res -> enrollment.setLastMessageId(res.messageId()))
                .then(markStepCompleteAndAdvance(enrollment, step.getStepIndex()));
    }

    private Mono<Void> handleWait(SequenceStep step, SequenceEnrollment enrollment) {
        Instant now = now();
        if (enrollment.getNextFireAt() == null) {
            Duration d = parseDuration(step.getWaitDuration());
            enrollment.setNextFireAt(now.plus(d));
            return enrollments.save(enrollment).then();
        }
        if (now.isBefore(enrollment.getNextFireAt())) {
            return Mono.empty();
        }
        enrollment.setNextFireAt(null);
        return markStepCompleteAndAdvance(enrollment, step.getStepIndex());
    }

    private Mono<Void> handleBranch(SequenceStep step, SequenceEnrollment enrollment) {
        String messageId = enrollment.getLastMessageId();
        Mono<Map<String, Object>> payloadMono = (messageId == null)
                ? Mono.just(Map.of())
                : engagements.findAllByTenantIdAndMessageIdOrderByEventAtDesc(
                                enrollment.getTenantId(), messageId)
                        .next()
                        .map(SequenceEngine::engagementPayload)
                        .defaultIfEmpty(Map.of());
        return payloadMono.flatMap(payload -> {
            boolean matched = ConditionEvaluator.matches(step.getBranchCondition(), payload);
            Integer target = matched ? step.getBranchTrueNextStep() : step.getBranchFalseNextStep();
            if (enrollment.getCompletedSteps() == null) {
                enrollment.setCompletedSteps(new ArrayList<>());
            }
            if (!enrollment.getCompletedSteps().contains(step.getStepIndex())) {
                enrollment.getCompletedSteps().add(step.getStepIndex());
            }
            if (target == null) {
                return complete(enrollment);
            }
            enrollment.setCurrentStepIndex(target);
            return enrollments.save(enrollment).then();
        });
    }

    private static Map<String, Object> engagementPayload(EmailEngagement evt) {
        Map<String, Object> p = new HashMap<>();
        p.put("event", evt.getEvent().name());
        p.put("recipient", evt.getRecipient());
        if (evt.getEventAt() != null) p.put("eventAt", evt.getEventAt().toString());
        return p;
    }

    private Mono<Void> complete(SequenceEnrollment enrollment) {
        enrollment.setStatus(SequenceEnrollment.Status.COMPLETED);
        return enrollments.save(enrollment).then();
    }

    private Mono<Void> markStepCompleteAndAdvance(SequenceEnrollment enrollment, int stepIndex) {
        if (enrollment.getCompletedSteps() == null) {
            enrollment.setCompletedSteps(new ArrayList<>());
        }
        if (!enrollment.getCompletedSteps().contains(stepIndex)) {
            enrollment.getCompletedSteps().add(stepIndex);
        }
        return advance(enrollment, enrollment.getCurrentStepIndex() + 1);
    }

    private Mono<Void> advance(SequenceEnrollment enrollment, int nextIndex) {
        enrollment.setCurrentStepIndex(nextIndex);
        return enrollments.save(enrollment).then();
    }


    private static Duration parseDuration(String iso) {
        if (iso == null || iso.isBlank()) return Duration.ZERO;
        try {
            return Duration.parse(iso);
        } catch (RuntimeException ex) {
            log.warn("Invalid waitDuration '{}', defaulting to 1 day", iso);
            return Duration.ofDays(1);
        }
    }

    private Instant now() {
        return clockProvider.getIfAvailable(Clock::systemUTC).instant();
    }
}
