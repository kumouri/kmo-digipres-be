package com.kumouri.kmodigipresbe.module.realestate.nurture;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1 — RE dormant-lead segmentation: proves the RE A/B/C/D dormancy thresholds (expressed as E1
 * {@code NurtureSegmentDefinition}s on the RE campaign — no engine hardcoding) bucket real-estate leads
 * correctly through the UNCHANGED {@code NurtureSegmentationService}, and that the TCPA opt-out skip + the
 * not-dormant-enough exclusion hold. The RE value band (A tier) is exercised via a WON deal.
 *
 * <p>The realestate module is enabled so the RE deployment is wired; segmentation itself is the shared
 * engine service (present when nurture is on by default). Fixed clock for deterministic dormancy.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, RealEstateNurtureSegmentationIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true"
})
class RealEstateNurtureSegmentationIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired ReactiveMongoTemplate mongo;
    @Autowired NurtureSegmentationService segmentation;
    @Autowired NurtureEnrollmentRepository enrollments;

    private UUID tenantId;
    private UUID campaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Deal.class).block();

        tenantId = UUID.randomUUID();
        // The RE campaign — the same A/B/C/D thresholds the demo seeder uses.
        NurtureCampaign campaign = mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("RE Reactivation").active(true)
                .segments(List.of(
                        new NurtureSegmentDefinition(DormancyBucket.A, 30, 90,
                                new BigDecimal("300000"), null),
                        new NurtureSegmentDefinition(DormancyBucket.B, 90, 180, null, null),
                        new NurtureSegmentDefinition(DormancyBucket.C, 180, 365, null, null),
                        new NurtureSegmentDefinition(DormancyBucket.D, 365, null, null, null)))
                .steps(List.of(new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                        "Hi {firstName}", null, null, false, 0)))
                .maxTouchesPerContactPerWindow(3)
                .build()).block();
        campaignId = campaign.getId();
    }

    /** A dormant lead: contact + a backdated CONTACT activity {@code daysDormant} days ago. */
    private UUID lead(String name, int daysDormant, boolean optedOut, BigDecimal wonValue) {
        UUID cid = UUID.randomUUID();
        Set<String> tags = optedOut
                ? Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG) : Set.of();
        mongo.save(Contact.builder()
                .id(cid).tenantId(tenantId).type(ContactType.PERSON)
                .firstName(name).displayName(name)
                .phones(List.of(PhoneNumber.builder().number("+12145550000").label("m").build()))
                .tags(tags)
                .build()).block();
        mongo.save(Activity.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ActivityType.NOTE).direction(ActivityDirection.OUTBOUND)
                .subjectType(SubjectType.CONTACT).subjectId(cid)
                .summary("last contact")
                .occurredAt(NOW.minus(daysDormant, ChronoUnit.DAYS))
                .build()).block();
        if (wonValue != null) {
            mongo.save(Deal.builder()
                    .id(UUID.randomUUID()).tenantId(tenantId)
                    .title("prior").stage(PipelineStage.WON).value(wonValue).currency("USD")
                    .primaryContactId(cid)
                    .build()).block();
        }
        return cid;
    }

    private DormancyBucket bucketOf(UUID contactId) {
        NurtureEnrollment enr = enrollments
                .findByTenantIdAndCampaignIdAndContactId(tenantId, campaignId, contactId).block();
        return enr == null ? null : enr.getBucket();
    }

    @Test
    void bucketsLeadsByRecencyAndValue_skipsOptOut_andNonDormant() {
        UUID aHighValue = lead("Ava", 45, false, new BigDecimal("420000")); // A (30-90d + >=300k)
        UUID aTooLowValue = lead("Al", 45, false, new BigDecimal("100000")); // value band misses A → falls through
        UUID b = lead("Daniel", 130, false, null);   // B (90-180d)
        UUID c = lead("Noah", 300, false, null);      // C (180-365d)
        UUID d = lead("Maya", 540, false, null);      // D (365d+)
        UUID optedOut = lead("Liam", 130, true, null); // would be B but opted-out → skipped
        UUID fresh = lead("New", 2, false, null);      // 2d dormant → no segment matches

        NurtureSegmentationService.SegmentationResult result =
                segmentation.segmentAndEnroll(tenantId, campaignId).block();

        assertThat(result).isNotNull();
        assertThat(result.skippedOptedOut()).isGreaterThanOrEqualTo(1);

        assertThat(bucketOf(aHighValue)).isEqualTo(DormancyBucket.A);
        assertThat(bucketOf(b)).isEqualTo(DormancyBucket.B);
        assertThat(bucketOf(c)).isEqualTo(DormancyBucket.C);
        assertThat(bucketOf(d)).isEqualTo(DormancyBucket.D);

        // The low-value 45d lead: A's value band excludes it; B starts at 90d → no segment matches → not enrolled.
        assertThat(bucketOf(aTooLowValue)).isNull();
        // Opted-out → never enrolled.
        assertThat(bucketOf(optedOut)).isNull();
        // Not dormant enough → not enrolled.
        assertThat(bucketOf(fresh)).isNull();
    }

    @Test
    void reRunIsIdempotent_noDuplicateEnrollment() {
        UUID b = lead("Daniel", 130, false, null);

        NurtureSegmentationService.SegmentationResult first =
                segmentation.segmentAndEnroll(tenantId, campaignId).block();
        assertThat(first.enrolled()).isGreaterThanOrEqualTo(1);

        NurtureSegmentationService.SegmentationResult second =
                segmentation.segmentAndEnroll(tenantId, campaignId).block();
        // Second run enrolls zero fresh + counts the existing one as already-enrolled.
        assertThat(second.enrolled()).isZero();
        assertThat(second.alreadyEnrolled()).isGreaterThanOrEqualTo(1);

        long count = enrollments.findAllByTenantIdAndCampaignId(tenantId, campaignId).count().block();
        assertThat(count).isEqualTo(1L);
    }
}
