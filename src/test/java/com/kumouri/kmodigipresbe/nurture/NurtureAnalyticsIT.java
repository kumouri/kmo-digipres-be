package com.kumouri.kmodigipresbe.nurture;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSendLog;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService.NurtureCampaignAnalytics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E1 — NurtureAnalyticsIT: seeds a campaign with enrollments across buckets + statuses + some send-log
 * rows, and asserts {@code NurtureAnalyticsService.summarize} reports the correct per-campaign and
 * per-bucket funnel counts + the total sent.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class NurtureAnalyticsIT {

    @Autowired ReactiveMongoTemplate mongo;
    @Autowired NurtureAnalyticsService analytics;

    private UUID tenantId;
    private UUID campaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureSendLog.class).block();
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("nurture-analytics-it-" + tenantId)
                .displayName("Nurture Analytics IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        NurtureCampaign campaign = mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).name("Reactivation").active(true)
                .build()).block();
        campaignId = campaign.getId();

        // Bucket A: 2 ACTIVE, 1 REPLIED, 1 BOOKED.
        seedEnrollment(DormancyBucket.A, NurtureEnrollmentStatus.ACTIVE);
        seedEnrollment(DormancyBucket.A, NurtureEnrollmentStatus.ACTIVE);
        seedEnrollment(DormancyBucket.A, NurtureEnrollmentStatus.REPLIED);
        UUID bookedA = seedEnrollment(DormancyBucket.A, NurtureEnrollmentStatus.BOOKED);
        // Bucket B: 1 OPTED_OUT, 1 COMPLETED.
        seedEnrollment(DormancyBucket.B, NurtureEnrollmentStatus.OPTED_OUT);
        UUID completedB = seedEnrollment(DormancyBucket.B, NurtureEnrollmentStatus.COMPLETED);

        // Send-logs: the booked-A enrollment got 2 touches; the completed-B got 3 — total 5.
        seedSendLogs(bookedA, 2);
        seedSendLogs(completedB, 3);

        // A second campaign's enrollment + send-log — must NOT leak into the summary.
        UUID otherCampaign = UUID.randomUUID();
        UUID otherEnr = mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).campaignId(otherCampaign)
                .contactId(UUID.randomUUID()).bucket(DormancyBucket.A)
                .status(NurtureEnrollmentStatus.ACTIVE).build()).block().getId();
        seedSendLogs(otherEnr, 4);
    }

    private UUID seedEnrollment(DormancyBucket bucket, NurtureEnrollmentStatus status) {
        return mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).campaignId(campaignId)
                .contactId(UUID.randomUUID()).bucket(bucket).status(status)
                .build()).block().getId();
    }

    private void seedSendLogs(UUID enrollmentId, int n) {
        for (int i = 0; i < n; i++) {
            mongo.save(NurtureSendLog.builder()
                    .id(UUID.randomUUID()).tenantId(tenantId).enrollmentId(enrollmentId)
                    .stepIndex(i).channel(NurtureChannel.SMS).contactId(UUID.randomUUID())
                    .sentAt(java.time.Instant.now())
                    .build()).block();
        }
    }

    @Test
    void summarize_reportsPerCampaignAndPerBucketFunnel() {
        NurtureCampaignAnalytics a = analytics.summarize(tenantId, campaignId).block();

        assertThat(a).isNotNull();
        assertThat(a.campaignId()).isEqualTo(campaignId);
        assertThat(a.name()).isEqualTo("Reactivation");
        assertThat(a.total()).isEqualTo(6);
        assertThat(a.active()).isEqualTo(2);
        assertThat(a.replied()).isEqualTo(1);
        assertThat(a.booked()).isEqualTo(1);
        assertThat(a.optedOut()).isEqualTo(1);
        assertThat(a.completed()).isEqualTo(1);
        assertThat(a.exited()).isZero();
        // sent = 2 (bookedA) + 3 (completedB) = 5; the other campaign's 4 must NOT leak in.
        assertThat(a.sent()).isEqualTo(5L);

        assertThat(a.perBucket()).containsKeys(DormancyBucket.A, DormancyBucket.B);
        assertThat(a.perBucket().get(DormancyBucket.A).total()).isEqualTo(4);
        assertThat(a.perBucket().get(DormancyBucket.A).active()).isEqualTo(2);
        assertThat(a.perBucket().get(DormancyBucket.A).replied()).isEqualTo(1);
        assertThat(a.perBucket().get(DormancyBucket.A).booked()).isEqualTo(1);
        assertThat(a.perBucket().get(DormancyBucket.B).total()).isEqualTo(2);
        assertThat(a.perBucket().get(DormancyBucket.B).optedOut()).isEqualTo(1);
        assertThat(a.perBucket().get(DormancyBucket.B).completed()).isEqualTo(1);
    }
}
