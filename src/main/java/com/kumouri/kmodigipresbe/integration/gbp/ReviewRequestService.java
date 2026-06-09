package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E3 Review Engine — review-REQUEST creation on completion. A dedicated {@code @PostConstruct}
 * subscriber on the two completion events that exist (the {@code RiskTieredPreventionService}
 * {@code events.stream().filter(type).flatMap(handle)} + synthetic {@link TenantContext} precedent):
 * <ul>
 *   <li>{@link DomainEventType#BOOKING_COMPLETED} (salon) — attribute to the stylist
 *       ({@link ReviewSubjectType#STAFF} + the payload {@code staffMemberId}), contact from the payload;</li>
 *   <li>{@link DomainEventType#MILESTONE_COMPLETED} (home/project) — attribute to the job
 *       ({@link ReviewSubjectType#PROJECT} + the payload {@code projectId}), contact resolved from
 *       {@code Project.primaryContactId}.</li>
 * </ul>
 * When a completion has no resolvable subjectId, attribution falls back to {@link ReviewSubjectType#OTHER}
 * keyed on the source entity id so a request is still created (every completed visit/job gets one ask).
 * A completion with no resolvable contact is skipped (nothing to text → no row).
 *
 * <h2>Creation idempotency — explicit-boolean, never {@code switchIfEmpty(create)}</h2>
 * Probes the unique {@code tenant_subject_contact_idx} on {@code (tenantId, subjectType, subjectId,
 * contactId)} ({@code findBy…(...).map(e->true).defaultIfEmpty(false)}) and inserts only if absent, with
 * an {@code onErrorResume(DuplicateKeyException → Mono.empty())} backstop for a concurrent / re-emitted
 * completion. So a completed booking fired twice (re-emit / restart) yields exactly one PENDING request.
 * The created request is PENDING with {@code dueAt = now + kmosf.review-engine.request-delay} — the
 * default-OFF {@link ReviewRequestSenderJob} sends it once due.
 *
 * <h2>§9 reactive</h2>
 * {@code @PostConstruct subscribe()} subscribes on {@code Schedulers.boundedElastic()} (never the Netty
 * loop); the visible-for-test {@link #handle(DomainEvent)} returns {@code Mono<Void>} the IT blocks. The
 * only {@code switchIfEmpty}-style construct is the explicit not-seen boolean default — no
 * {@code switchIfEmpty(create)}.
 *
 * <p>Always loaded (a plain {@code @Service}) — creating a PENDING row is harmless for a tenant that has
 * no review-engine deployment, because the <strong>sender</strong> is default-OFF, so nothing is ever
 * texted unless a deployment opts the sender in. (The {@code RiskTieredPreventionService} is module-bean-
 * gated because it sends immediately; the E3 split — always-create / default-OFF-send — needs no gate on
 * the creator.)
 */
@Slf4j
@Service
public class ReviewRequestService {

    private final DomainEventPublisher events;
    private final ReviewRequestRepository reviewRequests;
    private final ProjectRepository projects;
    private final Duration requestDelay;

    public ReviewRequestService(
            DomainEventPublisher events,
            ReviewRequestRepository reviewRequests,
            ProjectRepository projects,
            @Value("${kmosf.review-engine.request-delay:PT24H}") Duration requestDelay) {
        this.events = events;
        this.reviewRequests = reviewRequests;
        this.projects = projects;
        this.requestDelay = requestDelay;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.BOOKING_COMPLETED.equals(e.type())
                        || DomainEventType.MILESTONE_COMPLETED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("ReviewRequestService: error processing {} for tenant {}",
                                    e.type(), e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single completion event end-to-end and return when done (so an
     * IT can drive it deterministically without the live event bus + a sleep).
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        if (tenantId == null) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_REVIEW_ENGINE"));
        Mono<Void> work = DomainEventType.BOOKING_COMPLETED.equals(event.type())
                ? handleBookingCompleted(tenantId, event)
                : handleMilestoneCompleted(tenantId, event);
        return work.contextWrite(TenantContextHolder.write(ctx));
    }

    /** Salon: attribute to the stylist (STAFF + staffMemberId), contact from the payload. */
    private Mono<Void> handleBookingCompleted(UUID tenantId, DomainEvent event) {
        UUID contactId = asUuid(event.payload().get("contactId"));
        if (contactId == null) {
            return Mono.empty(); // nothing to text
        }
        UUID staffMemberId = asUuid(event.payload().get("staffMemberId"));
        UUID bookingId = asUuid(event.payload().get("bookingId"));
        ReviewSubjectType subjectType = staffMemberId != null
                ? ReviewSubjectType.STAFF : ReviewSubjectType.OTHER;
        UUID subjectId = staffMemberId != null ? staffMemberId : bookingId;
        if (subjectId == null) {
            return Mono.empty(); // no stable attribution anchor
        }
        return createIfAbsent(tenantId, subjectType, subjectId, contactId,
                DomainEventType.BOOKING_COMPLETED, bookingId);
    }

    /** Home/project: attribute to the job (PROJECT + projectId), contact from Project.primaryContactId. */
    private Mono<Void> handleMilestoneCompleted(UUID tenantId, DomainEvent event) {
        UUID projectId = asUuid(event.payload().get("projectId"));
        if (projectId == null) {
            return Mono.empty();
        }
        UUID milestoneId = asUuid(event.payload().get("milestoneId"));
        return projects.findByTenantIdAndId(tenantId, projectId)
                .flatMap(project -> {
                    UUID contactId = project.getPrimaryContactId();
                    if (contactId == null) {
                        return Mono.<Void>empty(); // no contact to text
                    }
                    return createIfAbsent(tenantId, ReviewSubjectType.PROJECT, projectId, contactId,
                            DomainEventType.MILESTONE_COMPLETED,
                            milestoneId != null ? milestoneId : projectId);
                });
    }

    /**
     * Explicit-boolean create over the unique {@code tenant_subject_contact_idx} — never
     * {@code switchIfEmpty(create)}. A concurrent / re-emitted completion loses the insert on a
     * {@code DuplicateKeyException} → {@code Mono.empty()} = exactly one PENDING request.
     */
    private Mono<Void> createIfAbsent(UUID tenantId, ReviewSubjectType subjectType, UUID subjectId,
                                      UUID contactId, String sourceEventType, UUID sourceRef) {
        return reviewRequests
                .findByTenantIdAndSubjectTypeAndSubjectIdAndContactId(
                        tenantId, subjectType, subjectId, contactId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (seen) {
                        log.debug("Review request already exists for tenant {} {}:{} contact {} — skip",
                                tenantId, subjectType, subjectId, contactId);
                        return Mono.empty();
                    }
                    ReviewRequest req = ReviewRequest.builder()
                            .tenantId(tenantId)
                            .subjectType(subjectType)
                            .subjectId(subjectId)
                            .contactId(contactId)
                            .sourceEventType(sourceEventType)
                            .sourceRef(sourceRef == null ? null : sourceRef.toString())
                            .status(ReviewRequest.Status.PENDING)
                            .dueAt(Instant.now().plus(requestDelay))
                            .build();
                    return reviewRequests.save(req)
                            .onErrorResume(DuplicateKeyException.class, ex -> {
                                log.debug("Review request concurrent-create lost for tenant {} {}:{} "
                                        + "contact {} — zero duplicate", tenantId, subjectType,
                                        subjectId, contactId);
                                return Mono.empty();
                            })
                            .doOnNext(saved -> emitCreated(tenantId, saved))
                            .then();
                });
    }

    private void emitCreated(UUID tenantId, ReviewRequest req) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("subjectType", req.getSubjectType() == null ? null : req.getSubjectType().name());
        if (req.getSubjectId() != null) payload.put("subjectId", req.getSubjectId().toString());
        if (req.getContactId() != null) payload.put("contactId", req.getContactId().toString());
        payload.put("sourceEventType", req.getSourceEventType());
        events.publish(DomainEvent.of(
                DomainEventType.REVIEW_REQUEST_CREATED, tenantId,
                req.getId() != null ? req.getId() : UUID.randomUUID(), payload));
    }

    private static UUID asUuid(Object raw) {
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }
}
