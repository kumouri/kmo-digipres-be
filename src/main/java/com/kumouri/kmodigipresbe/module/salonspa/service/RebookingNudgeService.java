package com.kumouri.kmodigipresbe.module.salonspa.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.model.sequence.SequenceStep;
import com.kumouri.kmodigipresbe.model.sequence.SequenceStepType;
import com.kumouri.kmodigipresbe.repository.SequenceRepository;
import com.kumouri.kmodigipresbe.service.sequence.SequenceCrudService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Subscribes to {@link DomainEventType#BOOKING_COMPLETED} and enrolls the
 * contact in the per-tenant "salon-rebook-30d" sequence. The sequence is
 * seeded lazily and idempotently on first use per tenant — no startup scan
 * of all tenants is required.
 *
 * <p>The seeded sequence has three steps:
 * <ol>
 *   <li>WAIT 30 days</li>
 *   <li>EMAIL_SEND — rebook reminder</li>
 *   <li>EXIT</li>
 * </ol>
 */
@Slf4j
@RequiredArgsConstructor
public class RebookingNudgeService {

    static final String SEQUENCE_NAME = "salon-rebook-30d";

    private final SequenceRepository sequences;
    private final SequenceCrudService sequenceCrud;
    private final DomainEventPublisher events;
    private final ReactiveMongoTemplate mongo;

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.BOOKING_COMPLETED.equals(e.type()))
                .flatMap(e -> handleBookingCompleted(e)
                        .onErrorResume(err -> {
                            log.error("RebookingNudgeService: error processing BOOKING_COMPLETED for tenant {}",
                                    e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private Mono<Void> handleBookingCompleted(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Object rawContactId = event.payload().get("contactId");
        if (!(rawContactId instanceof UUID contactId)) return Mono.empty();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return ensureSequence(tenantId)
                .flatMap(seq -> sequenceCrud.enroll(seq.getId(), contactId))
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Sequence> ensureSequence(UUID tenantId) {
        Query q = Query.query(
                Criteria.where("tenantId").is(tenantId).and("name").is(SEQUENCE_NAME));
        return mongo.findOne(q, Sequence.class)
                .switchIfEmpty(Mono.defer(() -> createSequence(tenantId)));
    }

    private Mono<Sequence> createSequence(UUID tenantId) {
        Sequence seq = Sequence.builder()
                .tenantId(tenantId)
                .name(SEQUENCE_NAME)
                .description("Re-book reminder: email contact 30 days after last completed visit")
                .status(Sequence.Status.ACTIVE)
                .steps(List.of(
                        SequenceStep.builder()
                                .stepIndex(0)
                                .type(SequenceStepType.WAIT)
                                .waitDuration("P30D")
                                .build(),
                        SequenceStep.builder()
                                .stepIndex(1)
                                .type(SequenceStepType.EMAIL_SEND)
                                .emailSubject("We miss you — time for your next visit?")
                                .emailHtmlBody("<p>Hi! It has been 30 days since your last visit. "
                                        + "We would love to see you again — click below to book.</p>")
                                .emailTextBody("Hi! It has been 30 days since your last visit. "
                                        + "We would love to see you again.")
                                .emailTag("salon-rebook-nudge")
                                .build(),
                        SequenceStep.builder()
                                .stepIndex(2)
                                .type(SequenceStepType.EXIT)
                                .build()))
                .build();
        return sequences.save(seq);
    }
}
