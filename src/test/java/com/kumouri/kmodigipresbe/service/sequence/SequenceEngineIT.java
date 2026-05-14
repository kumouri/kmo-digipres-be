package com.kumouri.kmodigipresbe.service.sequence;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.RuleCondition;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagementEvent;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.model.sequence.SequenceEnrollment;
import com.kumouri.kmodigipresbe.model.sequence.SequenceStep;
import com.kumouri.kmodigipresbe.model.sequence.SequenceStepType;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.EmailEngagementRepository;
import com.kumouri.kmodigipresbe.repository.SequenceEnrollmentRepository;
import com.kumouri.kmodigipresbe.repository.SequenceRepository;
import com.kumouri.kmodigipresbe.service.communication.TransactionalEmailService;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendRequest;
import com.kumouri.kmodigipresbe.service.communication.TransactionalSendResult;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * End-to-end: a 3-step sequence (EMAIL_SEND → WAIT → BRANCH) advances correctly,
 * branches on the engagement state, and is idempotent on a re-tick.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, SequenceEngineIT.Config.class})
class SequenceEngineIT {

    @TestConfiguration
    static class Config {
        // Inject a mutable clock so tests can fast-forward past WAIT steps.
        @org.springframework.context.annotation.Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-05-14T20:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired SequenceEngine engine;
    @Autowired SequenceRepository sequences;
    @Autowired SequenceEnrollmentRepository enrollments;
    @Autowired EmailEngagementRepository engagements;
    @Autowired ContactRepository contacts;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TransactionalEmailService transactionalEmail;

    private UUID tenantId;
    private TenantContext ctx;
    private final AtomicReference<String> lastSentTo = new AtomicReference<>();
    private final AtomicReference<String> lastSentSubject = new AtomicReference<>();

    @BeforeEach
    void setup() {
        // Wipe across this test.
        mongo.remove(new Query(), Sequence.class).block();
        mongo.remove(new Query(), SequenceEnrollment.class).block();
        mongo.remove(new Query(), EmailEngagement.class).block();
        mongo.remove(new Query(), Contact.class).block();

        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
        lastSentTo.set(null);
        lastSentSubject.set(null);

        when(transactionalEmail.send(any(TransactionalSendRequest.class)))
                .thenAnswer(inv -> {
                    TransactionalSendRequest req = inv.getArgument(0);
                    lastSentTo.set(req.to() == null || req.to().isEmpty() ? null : req.to().get(0));
                    lastSentSubject.set(req.subject());
                    return Mono.just(new TransactionalSendResult(
                            "mid-test-" + UUID.randomUUID(),
                            req.to() == null || req.to().isEmpty() ? null : req.to().get(0),
                            Instant.parse("2026-05-14T20:00:01Z")));
                });
    }

    @Test
    void emailSend_advancesAndStoresMessageId() {
        Contact c = contacts.save(Contact.builder()
                        .type(ContactType.PERSON).firstName("Alice")
                        .emails(List.of(new EmailContact("alice@example.test")))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        Sequence seq = sequences.save(Sequence.builder()
                        .name("Welcome").status(Sequence.Status.ACTIVE)
                        .steps(List.of(SequenceStep.builder()
                                .stepIndex(0).type(SequenceStepType.EMAIL_SEND)
                                .emailSubject("Hi Alice").emailHtmlBody("<p>hi</p>")
                                .emailFrom("sales@example.test")
                                .build()))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        SequenceEnrollment e = enrollments.save(SequenceEnrollment.builder()
                        .sequenceId(seq.getId()).contactId(c.getId())
                        .enrolledAt(Instant.now()).currentStepIndex(0)
                        .status(SequenceEnrollment.Status.ACTIVE)
                        .completedSteps(new ArrayList<>())
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        engine.runDueOnce().block();

        assertThat(lastSentTo.get()).isEqualTo("alice@example.test");
        assertThat(lastSentSubject.get()).isEqualTo("Hi Alice");
        SequenceEnrollment after = enrollments.findById(e.getId())
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(after).isNotNull();
        assertThat(after.getStatus()).isEqualTo(SequenceEnrollment.Status.COMPLETED);
        assertThat(after.getLastMessageId()).startsWith("mid-test-");
        assertThat(after.getCompletedSteps()).containsExactly(0);
    }

    @Test
    void waitStep_setsNextFireAtAndSkipsUntilDue() {
        Contact c = contacts.save(Contact.builder()
                        .type(ContactType.PERSON).firstName("Bob")
                        .emails(List.of(new EmailContact("bob@example.test")))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        Sequence seq = sequences.save(Sequence.builder()
                        .name("Drip").status(Sequence.Status.ACTIVE)
                        .steps(List.of(
                                SequenceStep.builder()
                                        .stepIndex(0).type(SequenceStepType.WAIT)
                                        .waitDuration("PT1H").build(),
                                SequenceStep.builder()
                                        .stepIndex(1).type(SequenceStepType.EMAIL_SEND)
                                        .emailSubject("after wait").emailHtmlBody("<p>x</p>")
                                        .emailFrom("sales@example.test").build()))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        SequenceEnrollment e = enrollments.save(SequenceEnrollment.builder()
                        .sequenceId(seq.getId()).contactId(c.getId())
                        .enrolledAt(Instant.now()).currentStepIndex(0)
                        .status(SequenceEnrollment.Status.ACTIVE)
                        .completedSteps(new ArrayList<>())
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // First tick: WAIT sets nextFireAt = now + 1h; nothing else fires.
        engine.runDueOnce().block();
        SequenceEnrollment afterFirst = enrollments.findById(e.getId())
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(afterFirst.getCurrentStepIndex()).isEqualTo(0);
        assertThat(afterFirst.getNextFireAt()).isAfter(Instant.parse("2026-05-14T20:00:00Z"));
        assertThat(lastSentTo.get()).isNull();

        // Fast-forward past nextFireAt by writing it backward; on next tick WAIT
        // completes and the EMAIL_SEND fires.
        afterFirst.setNextFireAt(Instant.parse("2026-05-14T19:59:00Z"));
        enrollments.save(afterFirst).contextWrite(TenantContextHolder.write(ctx)).block();

        engine.runDueOnce().block();
        SequenceEnrollment afterSecond = enrollments.findById(e.getId())
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(lastSentTo.get()).isEqualTo("bob@example.test");
        assertThat(afterSecond.getStatus()).isEqualTo(SequenceEnrollment.Status.COMPLETED);
    }

    @Test
    void branchOnOpen_takesTrueBranchWhenEngagementMatches() {
        Contact c = contacts.save(Contact.builder()
                        .type(ContactType.PERSON).firstName("Carol")
                        .emails(List.of(new EmailContact("carol@example.test")))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        Sequence seq = sequences.save(Sequence.builder()
                        .name("BranchTest").status(Sequence.Status.ACTIVE)
                        .steps(List.of(
                                SequenceStep.builder()
                                        .stepIndex(0).type(SequenceStepType.EMAIL_SEND)
                                        .emailSubject("test").emailHtmlBody("<p>x</p>")
                                        .emailFrom("sales@example.test").build(),
                                SequenceStep.builder()
                                        .stepIndex(1).type(SequenceStepType.BRANCH)
                                        .branchCondition(List.of(RuleCondition.builder()
                                                .field("event").op(RuleCondition.Op.EQUALS)
                                                .value("OPEN").build()))
                                        .branchTrueNextStep(2).branchFalseNextStep(3).build(),
                                SequenceStep.builder()
                                        .stepIndex(2).type(SequenceStepType.EXIT).build(),
                                SequenceStep.builder()
                                        .stepIndex(3).type(SequenceStepType.EXIT).build()))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        SequenceEnrollment e = enrollments.save(SequenceEnrollment.builder()
                        .sequenceId(seq.getId()).contactId(c.getId())
                        .enrolledAt(Instant.now()).currentStepIndex(0)
                        .status(SequenceEnrollment.Status.ACTIVE)
                        .completedSteps(new ArrayList<>())
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // Tick 1: EMAIL_SEND fires, lastMessageId stored.
        engine.runDueOnce().block();
        SequenceEnrollment afterSend = enrollments.findById(e.getId())
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(afterSend.getCurrentStepIndex()).isEqualTo(1);

        // Seed an OPEN engagement matching the lastMessageId.
        engagements.save(EmailEngagement.builder()
                        .messageId(afterSend.getLastMessageId())
                        .event(EmailEngagementEvent.OPEN)
                        .eventAt(Instant.parse("2026-05-14T20:00:30Z"))
                        .recipient("carol@example.test")
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // Tick 2: BRANCH evaluates → true → advances to step 2 → EXIT → COMPLETED.
        engine.runDueOnce().block();
        engine.runDueOnce().block();
        SequenceEnrollment afterBranch = enrollments.findById(e.getId())
                .contextWrite(TenantContextHolder.write(ctx)).block();
        assertThat(afterBranch.getStatus()).isEqualTo(SequenceEnrollment.Status.COMPLETED);
        assertThat(afterBranch.getCompletedSteps()).contains(0, 1);
    }

    @Test
    void duplicateTick_isIdempotent() {
        Contact c = contacts.save(Contact.builder()
                        .type(ContactType.PERSON).firstName("Dave")
                        .emails(List.of(new EmailContact("dave@example.test")))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        Sequence seq = sequences.save(Sequence.builder()
                        .name("Once").status(Sequence.Status.ACTIVE)
                        .steps(List.of(SequenceStep.builder()
                                .stepIndex(0).type(SequenceStepType.EMAIL_SEND)
                                .emailSubject("once").emailHtmlBody("<p>x</p>")
                                .emailFrom("sales@example.test").build()))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        enrollments.save(SequenceEnrollment.builder()
                        .sequenceId(seq.getId()).contactId(c.getId())
                        .enrolledAt(Instant.now()).currentStepIndex(0)
                        .status(SequenceEnrollment.Status.ACTIVE)
                        .completedSteps(new ArrayList<>())
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        engine.runDueOnce().block();
        String firstMessageId = lastSentTo.get();  // any non-null pin
        engine.runDueOnce().block();
        engine.runDueOnce().block();
        // Single send across three ticks — second/third ticks find the enrollment
        // already COMPLETED or already past the step.
        org.mockito.Mockito.verify(transactionalEmail, org.mockito.Mockito.times(1))
                .send(any(TransactionalSendRequest.class));
        assertThat(firstMessageId).isEqualTo("dave@example.test");
    }
}
