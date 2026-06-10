package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T11 (Home "QuoteCloser") — the won-job reaction. A dedicated {@code @PostConstruct} subscriber on
 * {@link DomainEventType#QUOTE_ACCEPTED} (emitted by the UNCHANGED T8 {@code QuoteBookingService} when a
 * homeowner accepts their quote) — a structural mirror of the T3 {@code TierRoutingService} +
 * E3 {@code ReviewRequestService} ({@code events.stream().filter(type).flatMap(handle)} + synthetic
 * {@link TenantContext} + a visible-for-test {@link #handle(DomainEvent)}).
 *
 * <h2>Two effects on accept (composition — both reuse shipped cores)</h2>
 * <ol>
 *   <li><strong>Stop the cadence</strong> — if the accepting contact has a non-terminal enrollment in the
 *       tenant's configured QuoteCloser {@code NurtureCampaign} (E1), exit it ({@code EXITED}, reason
 *       "quote accepted"). The {@code NurtureRunner} naturally stops touching an {@code EXITED} enrollment —
 *       the moment the quote is won, the nudge cadence stops (no runner edit). A recovery (a quote accepted
 *       while/after a nudge) emits {@link DomainEventType#QUOTE_CLOSER_RECOVERED}.</li>
 *   <li><strong>Request a review</strong> — create exactly one E3 {@link ReviewRequest} for the won job
 *       ({@link ReviewSubjectType#OTHER} keyed on the quote id, contact from the event). The shipped
 *       default-OFF {@code ReviewRequestSenderJob} delivers it — T11 adds <strong>zero</strong> send logic.
 *       The won-job review is config-independent (it fires even with no {@code QuoteCloserConfig} row).</li>
 * </ol>
 *
 * <h2>Idempotency — explicit-boolean, NEVER {@code switchIfEmpty(create/send)}</h2>
 * The review-request create is the E3 explicit-boolean probe over the unique {@code tenant_subject_contact_idx}
 * ({@code findBy…(...).map(true).defaultIfEmpty(false)}) + an {@code onErrorResume(DuplicateKeyException →
 * empty)} backstop (the {@code ReviewRequestService.createIfAbsent} pattern). So a re-fired / re-emitted
 * {@code QUOTE_ACCEPTED} (restart, concurrent emit) yields <strong>exactly one</strong> review request. The
 * cadence stop is naturally idempotent (an already-terminal enrollment is left untouched; no recovery
 * re-emit). NEVER {@code switchIfEmpty(create/send)}.
 *
 * <h2>§9 reactive</h2>
 * {@code @PostConstruct subscribe()} subscribes on {@code Schedulers.boundedElastic()} (never the Netty
 * loop); the visible-for-test {@link #handle(DomainEvent)} returns {@code Mono<Void>} the IT blocks. The
 * whole chain is wrapped {@code onErrorResume} so a glue failure never corrupts the accept path (the
 * {@code QuoteBookingService} accept already committed before this fires). Hand-constructed as a
 * {@code @Bean} by {@code QuoteCloserAutoConfiguration}; the {@code @PostConstruct} fires the bus
 * subscription at init.
 */
@Slf4j
public class QuoteWonSubscriber {

    public static final String SYSTEM_ROLE = "AUTOMATION_QUOTE_CLOSER";

    private final DomainEventPublisher events;
    private final QuoteCloserConfigRepository configs;
    private final NurtureEnrollmentRepository enrollments;
    private final ReviewRequestRepository reviewRequests;
    private final Duration requestDelay;

    public QuoteWonSubscriber(DomainEventPublisher events,
                              QuoteCloserConfigRepository configs,
                              NurtureEnrollmentRepository enrollments,
                              ReviewRequestRepository reviewRequests,
                              Duration requestDelay) {
        this.events = events;
        this.configs = configs;
        this.enrollments = enrollments;
        this.reviewRequests = reviewRequests;
        this.requestDelay = requestDelay;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.QUOTE_ACCEPTED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("QuoteWonSubscriber: error processing QUOTE_ACCEPTED for tenant {}",
                                    e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code QUOTE_ACCEPTED} event end-to-end (stop the cadence +
     * request a review) and return when done (so an IT can drive it deterministically without the live event
     * bus + a sleep). A missing tenant / contact is a clean no-op for the affected leg.
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        if (tenantId == null) {
            return Mono.empty();
        }
        UUID quoteRequestId = asUuid(event.payload().get("quoteRequestId"));
        UUID contactId = asUuid(event.payload().get("contactId"));
        TenantContext ctx = new TenantContext(tenantId, null, Set.of(SYSTEM_ROLE));
        return stopCadence(tenantId, quoteRequestId, contactId)
                .then(requestReview(tenantId, quoteRequestId, contactId))
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /**
     * Exit the contact's non-terminal QuoteCloser-campaign enrollment (cadence stop) + emit
     * {@code QUOTE_CLOSER_RECOVERED}. No config / no enrollment ⇒ clean no-op (nothing to stop, no recovery).
     */
    private Mono<Void> stopCadence(UUID tenantId, UUID quoteRequestId, UUID contactId) {
        if (contactId == null) {
            return Mono.empty();
        }
        return configs.findByTenantId(tenantId)
                .flatMap(config -> {
                    UUID campaignId = config.getCampaignId();
                    if (campaignId == null) {
                        return Mono.empty();
                    }
                    return enrollments.findByTenantIdAndCampaignIdAndContactId(tenantId, campaignId, contactId)
                            .filter(enr -> enr.getStatus() == null || !enr.getStatus().isTerminal())
                            .flatMap(enr -> enrollments.save(enr.toBuilder()
                                            .status(NurtureEnrollmentStatus.EXITED)
                                            .exitedReason("quote accepted")
                                            .nextFireAt(null)
                                            .build())
                                    .doOnNext(saved -> events.publish(DomainEvent.of(
                                            DomainEventType.QUOTE_CLOSER_RECOVERED, tenantId, saved.getId(),
                                            recoveredPayload(quoteRequestId, contactId, campaignId))))
                                    .then());
                })
                .then();
    }

    /**
     * Create exactly one E3 {@link ReviewRequest} for the won job — explicit-boolean over the unique
     * {@code tenant_subject_contact_idx}, NEVER {@code switchIfEmpty(create)}. The shipped default-OFF
     * {@code ReviewRequestSenderJob} delivers it. A re-fired event yields exactly one request.
     */
    private Mono<Void> requestReview(UUID tenantId, UUID quoteRequestId, UUID contactId) {
        if (quoteRequestId == null || contactId == null) {
            // No stable attribution anchor / no contact to text — nothing to request.
            return Mono.empty();
        }
        return reviewRequests.findByTenantIdAndSubjectTypeAndSubjectIdAndContactId(
                        tenantId, ReviewSubjectType.OTHER, quoteRequestId, contactId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (seen) {
                        log.debug("QuoteCloser: review request already exists for tenant {} quote {} "
                                + "contact {} — skip", tenantId, quoteRequestId, contactId);
                        return Mono.empty();
                    }
                    ReviewRequest req = ReviewRequest.builder()
                            .tenantId(tenantId)
                            .subjectType(ReviewSubjectType.OTHER)
                            .subjectId(quoteRequestId)
                            .contactId(contactId)
                            .sourceEventType(DomainEventType.QUOTE_ACCEPTED)
                            .sourceRef(quoteRequestId.toString())
                            .status(ReviewRequest.Status.PENDING)
                            .dueAt(Instant.now().plus(requestDelay))
                            .build();
                    return reviewRequests.save(req)
                            .onErrorResume(DuplicateKeyException.class, ex -> {
                                log.debug("QuoteCloser: review request concurrent-create lost for tenant {} "
                                        + "quote {} contact {} — zero duplicate", tenantId, quoteRequestId,
                                        contactId);
                                return Mono.empty();
                            })
                            .doOnNext(saved -> emitReviewRequested(tenantId, quoteRequestId, contactId, saved))
                            .then();
                });
    }

    private void emitReviewRequested(UUID tenantId, UUID quoteRequestId, UUID contactId, ReviewRequest req) {
        Map<String, Object> p = new HashMap<>();
        p.put("quoteRequestId", quoteRequestId.toString());
        p.put("contactId", contactId.toString());
        if (req.getId() != null) p.put("reviewRequestId", req.getId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.QUOTE_CLOSER_REVIEW_REQUESTED, tenantId,
                req.getId() != null ? req.getId() : quoteRequestId, p));
    }

    private static Map<String, Object> recoveredPayload(UUID quoteRequestId, UUID contactId, UUID campaignId) {
        Map<String, Object> p = new HashMap<>();
        if (quoteRequestId != null) p.put("quoteRequestId", quoteRequestId.toString());
        if (contactId != null) p.put("contactId", contactId.toString());
        if (campaignId != null) p.put("campaignId", campaignId.toString());
        return p;
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
