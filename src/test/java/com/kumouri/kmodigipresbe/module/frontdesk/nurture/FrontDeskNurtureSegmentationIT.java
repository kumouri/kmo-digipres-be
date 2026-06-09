package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

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
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
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

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2 — health lapsed-patient segmentation: proves the health A/B/C/D lapse thresholds (expressed as E1
 * {@code NurtureSegmentDefinition}s on the health campaign — no engine hardcoding) bucket patients correctly
 * through the UNCHANGED {@code NurtureSegmentationService} on <strong>logistics only</strong>, and that the
 * TCPA opt-out skip + the not-lapsed-enough exclusion hold. The A-tier value band is exercised via a prior
 * WON deal (lifetime spend — a logistics/value signal, not clinical). The health twin of
 * {@code RealEstateNurtureSegmentationIT}.
 *
 * <p>Also asserts the <strong>PHI-free-by-construction</strong> property directly: the {@link Appointment}
 * model the demo seeds carries no clinically-named field, so segmentation (and the whole module) literally
 * cannot read a diagnosis/procedure — the boundary is enforced at the data layer.
 *
 * <p>The frontdesk module is enabled so the deployment is wired; segmentation itself is the shared engine
 * service (present when nurture is on by default). Fixed clock for deterministic dormancy.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FrontDeskNurtureSegmentationIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true"
})
class FrontDeskNurtureSegmentationIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    /** Clinically-named field stems that must never appear on the PHI-free Appointment model (fence F1). */
    private static final List<String> CLINICAL_FIELD_STEMS = List.of(
            "diagnos", "procedure", "treatment", "chiefcomplaint", "complaint", "clinical",
            "symptom", "medication", "prescription", "condition", "icd", "cpt");

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
        mongo.remove(new Query(), Appointment.class).block();

        tenantId = UUID.randomUUID();
        // The health campaign — the same A/B/C/D thresholds the demo seeder uses (logistics + value only).
        NurtureCampaign campaign = mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Health Reactivation").active(true)
                .segments(List.of(
                        new NurtureSegmentDefinition(DormancyBucket.A, 90, 180,
                                new BigDecimal("1500"), null),
                        new NurtureSegmentDefinition(DormancyBucket.B, 180, 365, null, null),
                        new NurtureSegmentDefinition(DormancyBucket.C, 365, 540, null, null),
                        new NurtureSegmentDefinition(DormancyBucket.D, 540, null, null, null)))
                .steps(List.of(new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                        "Hi {firstName}", null, null, false, 0)))
                .maxTouchesPerContactPerWindow(3)
                .build()).block();
        campaignId = campaign.getId();
    }

    /**
     * A lapsed patient: contact + a backdated CONTACT activity {@code daysLapsed} days ago (the recency the
     * engine reads) + a PHI-free COMPLETED Appointment (logistics only). Optional WON deal for the value band.
     */
    private UUID patient(String name, int daysLapsed, boolean optedOut, BigDecimal wonValue) {
        UUID cid = UUID.randomUUID();
        Set<String> tags = optedOut
                ? Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG) : Set.of();
        mongo.save(Contact.builder()
                .id(cid).tenantId(tenantId).type(ContactType.PERSON)
                .firstName(name).displayName(name)
                .phones(List.of(PhoneNumber.builder().number("+13145550000").label("m").build()))
                .tags(tags)
                .build()).block();
        Instant lastSeen = NOW.minus(daysLapsed, ChronoUnit.DAYS);
        mongo.save(Activity.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ActivityType.NOTE).direction(ActivityDirection.OUTBOUND)
                .subjectType(SubjectType.CONTACT).subjectId(cid)
                .summary("last visit")
                .occurredAt(lastSeen)
                .build()).block();
        mongo.save(Appointment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).contactId(cid)
                .status(AppointmentStatus.COMPLETED).visitTypeBucket(VisitTypeBucket.RECALL)
                .scheduledStart(lastSeen).scheduledEnd(lastSeen.plus(30, ChronoUnit.MINUTES))
                .lastVisitAt(lastSeen)
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
    void bucketsPatientsByRecencyAndValue_skipsOptOut_andNotLapsed() {
        UUID aHighValue = patient("Ava", 100, false, new BigDecimal("2200"));   // A (90-180d + >=1500)
        UUID aTooLowValue = patient("Al", 100, false, new BigDecimal("400"));    // value band misses A → falls through
        UUID b = patient("Daniel", 260, false, null);   // B (180-365d)
        UUID c = patient("Noah", 460, false, null);      // C (365-540d)
        UUID d = patient("Maya", 700, false, null);      // D (540d+)
        UUID optedOut = patient("Liam", 260, true, null); // would be B but opted-out → skipped
        UUID fresh = patient("New", 10, false, null);     // 10d lapsed → no segment matches

        NurtureSegmentationService.SegmentationResult result =
                segmentation.segmentAndEnroll(tenantId, campaignId).block();

        assertThat(result).isNotNull();
        assertThat(result.skippedOptedOut()).isGreaterThanOrEqualTo(1);

        assertThat(bucketOf(aHighValue)).isEqualTo(DormancyBucket.A);
        assertThat(bucketOf(b)).isEqualTo(DormancyBucket.B);
        assertThat(bucketOf(c)).isEqualTo(DormancyBucket.C);
        assertThat(bucketOf(d)).isEqualTo(DormancyBucket.D);

        // The low-value 100d patient: A's value band excludes it; B starts at 180d → no segment → not enrolled.
        assertThat(bucketOf(aTooLowValue)).isNull();
        // Opted-out → never enrolled.
        assertThat(bucketOf(optedOut)).isNull();
        // Not lapsed enough → not enrolled.
        assertThat(bucketOf(fresh)).isNull();
    }

    @Test
    void reRunIsIdempotent_noDuplicateEnrollment() {
        patient("Daniel", 260, false, null);

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

    /**
     * PHI-free by construction (fence F1): the Appointment model the module reads for the dormant cohort
     * carries NO clinically-named field, so segmentation literally cannot use a diagnosis/procedure feature.
     */
    @Test
    void appointmentModel_hasNoClinicalField_segmentationIsLogisticsOnly() {
        for (Field f : Appointment.class.getDeclaredFields()) {
            String name = f.getName().toLowerCase(Locale.ROOT);
            for (String stem : CLINICAL_FIELD_STEMS) {
                assertThat(name)
                        .as("Appointment must carry no clinically-named field (found '%s' matching '%s')",
                                f.getName(), stem)
                        .doesNotContain(stem);
            }
        }
    }
}
