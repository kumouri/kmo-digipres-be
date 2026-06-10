package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11 (Home "QuoteCloser") — {@link QuoteCloserAnalyticsService}: the abandonment + recovery funnel. Seeds a
 * deterministic funnel and asserts the counts + the recovery rate.
 *
 * <p>Scenario: 4 quotes. 2 contacts are QuoteCloser enrollees (followed-up); of those, 1 quote is now
 * ACCEPTED (recovered). 1 review request exists (sourceEventType {@code quote.accepted}). So:
 * quotesSent=4, followedUp=2, recovered=1, reviewRequested=1, recoveryRate=0.50.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.modules.nurture.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteCloserAnalyticsIT {

    @Autowired QuoteCloserAnalyticsService analyticsService;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID campaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), QuoteRequest.class).block();
        mongo.remove(new Query(), QuoteCloserConfig.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("quote-closer-analytics-it-" + tenantId)
                .displayName("QuoteCloser Analytics IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("quoting", "nurture")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        campaignId = UUID.randomUUID();
        mongo.save(NurtureCampaign.builder()
                .id(campaignId).tenantId(tenantId).name("QuoteCloser").vertical("home").active(true)
                .build()).block();
        mongo.save(QuoteCloserConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).unacceptedWindowHours(24)
                .build()).block();
    }

    private void seedQuote(UUID contactId, QuoteStatus status) {
        mongo.save(QuoteRequest.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactId(contactId).status(status)
                .build()).block();
    }

    private void seedEnrollment(UUID contactId, NurtureEnrollmentStatus status) {
        mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).contactId(contactId)
                .bucket(DormancyBucket.A).currentStepIndex(0).status(status)
                .build()).block();
    }

    private void seedReviewRequest(UUID contactId, UUID quoteId) {
        mongo.save(ReviewRequest.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .subjectType(ReviewSubjectType.OTHER).subjectId(quoteId).contactId(contactId)
                .sourceEventType("quote.accepted").status(ReviewRequest.Status.PENDING)
                .dueAt(Instant.now())
                .build()).block();
    }

    @Test
    void funnel_countsSentFollowedUpRecoveredReviewed_andRate() {
        UUID c1 = UUID.randomUUID(); // enrolled + recovered (ACCEPTED)
        UUID c2 = UUID.randomUUID(); // enrolled, NOT recovered (still NEW)
        UUID c3 = UUID.randomUUID(); // never enrolled, NEW
        UUID c4 = UUID.randomUUID(); // never enrolled, NEW

        UUID acceptedQuote = UUID.randomUUID();
        // c1: one ACCEPTED quote (recovered).
        mongo.save(QuoteRequest.builder()
                .id(acceptedQuote).tenantId(tenantId).contactId(c1).status(QuoteStatus.ACCEPTED)
                .build()).block();
        seedQuote(c2, QuoteStatus.NEW);
        seedQuote(c3, QuoteStatus.NEW);
        seedQuote(c4, QuoteStatus.NEW);

        // c1 + c2 are QuoteCloser enrollees (followed-up).
        seedEnrollment(c1, NurtureEnrollmentStatus.EXITED); // exited on accept
        seedEnrollment(c2, NurtureEnrollmentStatus.ACTIVE);

        // One review request from the won quote.
        seedReviewRequest(c1, acceptedQuote);

        QuoteCloserAnalytics a = analyticsService.analytics(tenantId).block();

        assertThat(a).isNotNull();
        assertThat(a.quotesSent()).isEqualTo(4);
        assertThat(a.followedUp()).isEqualTo(2);
        assertThat(a.recovered()).isEqualTo(1);
        assertThat(a.reviewRequested()).isEqualTo(1);
        assertThat(a.recoveryRate()).isEqualByComparingTo(new BigDecimal("0.50"));
    }

    @Test
    void funnel_noFollowUp_rateIsZero() {
        seedQuote(UUID.randomUUID(), QuoteStatus.NEW);
        seedQuote(UUID.randomUUID(), QuoteStatus.NEW);

        QuoteCloserAnalytics a = analyticsService.analytics(tenantId).block();

        assertThat(a.quotesSent()).isEqualTo(2);
        assertThat(a.followedUp()).isEqualTo(0);
        assertThat(a.recovered()).isEqualTo(0);
        assertThat(a.recoveryRate()).isEqualByComparingTo(new BigDecimal("0.00"));
    }
}
