package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11 (Home "QuoteCloser") — {@link QuoteWonSubscriber}: the {@code QUOTE_ACCEPTED} reaction. Drives the
 * visible-for-test {@link QuoteWonSubscriber#handle(DomainEvent)} deterministically (no live bus + sleep).
 *
 * <p>Proves the two composition effects on accept:
 * <ul>
 *   <li><strong>stop the cadence</strong> — a still-active QuoteCloser enrollment for the accepting contact
 *       is EXITED (reason "quote accepted") so the {@code NurtureRunner} sends no further touch;</li>
 *   <li><strong>request a review</strong> — exactly ONE E3 {@link ReviewRequest} ({@code OTHER}, the quote
 *       id, the contact, sourceEventType {@code quote.accepted}, PENDING) is created;</li>
 *   <li><strong>idempotent</strong> — a re-fired {@code QUOTE_ACCEPTED} yields still exactly one review
 *       request (the explicit-boolean {@code tenant_subject_contact_idx} probe) and no second exit;</li>
 *   <li><strong>config-independent review</strong> — the review request is created even with no
 *       {@code QuoteCloserConfig} (the won-job ask does not depend on the cadence config).</li>
 * </ul>
 * §7: no live send — the E3 sender that would deliver the request is default-OFF and not enabled here.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.modules.nurture.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteCloserWonIT {

    @Autowired QuoteWonSubscriber subscriber;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID campaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), QuoteCloserConfig.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("quote-closer-won-it-" + tenantId)
                .displayName("QuoteCloser Won IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("quoting", "nurture")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        campaignId = UUID.randomUUID();
        mongo.save(NurtureCampaign.builder()
                .id(campaignId).tenantId(tenantId)
                .name("QuoteCloser").vertical("home").active(true)
                .build()).block();
        mongo.save(QuoteCloserConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).unacceptedWindowHours(24)
                .build()).block();
    }

    private DomainEvent acceptedEvent(UUID quoteId, UUID contactId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("quoteRequestId", quoteId.toString());
        payload.put("contactId", contactId.toString());
        payload.put("recommendation", "REPLACE");
        payload.put("bookingLinkSent", true);
        return DomainEvent.of(DomainEventType.QUOTE_ACCEPTED, tenantId, quoteId, payload);
    }

    private UUID seedActiveEnrollment(UUID contactId) {
        UUID id = UUID.randomUUID();
        mongo.save(NurtureEnrollment.builder()
                .id(id).tenantId(tenantId)
                .campaignId(campaignId).contactId(contactId)
                .bucket(DormancyBucket.A).currentStepIndex(1)
                .status(NurtureEnrollmentStatus.ACTIVE)
                .nextFireAt(java.time.Instant.now())
                .build()).block();
        return id;
    }

    private List<ReviewRequest> reviewRequestsFor(UUID quoteId, UUID contactId) {
        return mongo.find(new Query(Criteria.where("tenantId").is(tenantId)
                                .and("subjectType").is(ReviewSubjectType.OTHER)
                                .and("subjectId").is(quoteId)
                                .and("contactId").is(contactId)),
                        ReviewRequest.class)
                .collectList().block();
    }

    @Test
    void accept_stopsCadence_andCreatesOneReviewRequest() {
        UUID contactId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        UUID enrollmentId = seedActiveEnrollment(contactId);

        subscriber.handle(acceptedEvent(quoteId, contactId)).block();

        // Cadence stopped.
        NurtureEnrollment enr = mongo.findById(enrollmentId, NurtureEnrollment.class).block();
        assertThat(enr.getStatus()).as("accept → cadence EXITED").isEqualTo(NurtureEnrollmentStatus.EXITED);
        assertThat(enr.getExitedReason()).isEqualTo("quote accepted");
        assertThat(enr.getNextFireAt()).as("no further runner tick").isNull();

        // Exactly one review request for the won job.
        List<ReviewRequest> reqs = reviewRequestsFor(quoteId, contactId);
        assertThat(reqs).hasSize(1);
        ReviewRequest req = reqs.get(0);
        assertThat(req.getStatus()).isEqualTo(ReviewRequest.Status.PENDING);
        assertThat(req.getSourceEventType()).isEqualTo("quote.accepted");
        assertThat(req.getDueAt()).isNotNull();
    }

    @Test
    void reFiredAccept_yieldsExactlyOneReviewRequest_idempotent() {
        UUID contactId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();
        seedActiveEnrollment(contactId);

        subscriber.handle(acceptedEvent(quoteId, contactId)).block();
        subscriber.handle(acceptedEvent(quoteId, contactId)).block(); // re-emit / restart

        assertThat(reviewRequestsFor(quoteId, contactId))
                .as("idempotent — exactly one review request after two QUOTE_ACCEPTED").hasSize(1);
    }

    @Test
    void accept_withNoEnrollment_stillCreatesReviewRequest() {
        // No QuoteCloser enrollment for this contact — the won-job review is still created (the ask does not
        // depend on whether the quote was ever in the cadence).
        UUID contactId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        subscriber.handle(acceptedEvent(quoteId, contactId)).block();

        assertThat(reviewRequestsFor(quoteId, contactId)).hasSize(1);
    }

    @Test
    void accept_withNoConfig_stillCreatesReviewRequest_noStop() {
        // No QuoteCloserConfig at all — the review leg is config-independent.
        mongo.remove(new Query(), QuoteCloserConfig.class).block();
        UUID contactId = UUID.randomUUID();
        UUID quoteId = UUID.randomUUID();

        subscriber.handle(acceptedEvent(quoteId, contactId)).block();

        assertThat(reviewRequestsFor(quoteId, contactId))
                .as("review request fires even with no config").hasSize(1);
    }
}
