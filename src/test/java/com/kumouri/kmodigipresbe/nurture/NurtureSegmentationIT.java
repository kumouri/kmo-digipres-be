package com.kumouri.kmodigipresbe.nurture;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
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
import reactor.core.publisher.Mono;

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
 * E1 — NurtureSegmentationIT: drives {@code NurtureSegmentationService.segmentAndEnroll} and proves the
 * vertical-agnostic dormancy bucketing, the TCPA opt-out skip, the explicit-boolean find-or-enroll
 * idempotency (re-run = zero duplicate, the unique-index backstop), and the optional WON-deal
 * value-band segment.
 *
 * <p>Uses a fixed {@code @Primary} Clock (the {@code RecurringInvoiceOccurrenceCountIT} precedent) so
 * days-since-last-activity is deterministic. Shard-safe: no send mocks needed (segmentation sends
 * nothing); self-clean {@code mongo.remove} {@code @BeforeEach}.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NurtureSegmentationIT.FixedClockConfig.class})
class NurtureSegmentationIT {

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

    @BeforeEach
    void clean() {
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Deal.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("nurture-seg-it-" + tenantId)
                .displayName("Nurture Seg IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
    }

    /** Persist a contact + (optionally) a last-activity that many days before NOW. */
    private UUID seedContact(String name, Long daysSinceActivity, Set<String> tags) {
        UUID cid = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(cid).tenantId(tenantId).type(ContactType.PERSON)
                .firstName(name).displayName(name)
                .tags(tags == null ? Set.of() : tags)
                .build()).block();
        if (daysSinceActivity != null) {
            mongo.save(Activity.builder()
                    .id(UUID.randomUUID()).tenantId(tenantId)
                    .type(ActivityType.NOTE)
                    .subjectType(SubjectType.CONTACT).subjectId(cid)
                    .summary("seed activity")
                    .occurredAt(NOW.minus(daysSinceActivity, ChronoUnit.DAYS))
                    .build()).block();
        }
        return cid;
    }

    private NurtureCampaign seedCampaign(List<NurtureSegmentDefinition> segments) {
        return mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Reactivation").active(true)
                .segments(segments)
                .steps(List.of(new NurtureCadenceStep(
                        0, NurtureChannel.SMS, 0, "Hi {firstName}, still need help?",
                        null, null, false, 0)))
                .maxTouchesPerContactPerWindow(2)
                .build()).block();
    }

    private <T> T asTenant(Mono<T> mono) {
        return mono.contextWrite(TenantContextHolder.write(
                new TenantContext(tenantId, null, Set.of("TEST")))).block();
    }

    @Test
    void bucketsByDormancy_skipsOptedOut_isIdempotent() {
        // Bucket A = [30,90) days dormant; Bucket B = [90, null) days dormant.
        UUID aContact = seedContact("Recent Dormant", 45L, null);       // -> A
        UUID bContact = seedContact("Cold", 200L, null);                // -> B
        seedContact("Too Fresh", 5L, null);                              // no match (< 30)
        UUID optedOut = seedContact("Opted Out", 60L,
                Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG));    // skipped (TCPA)

        NurtureCampaign campaign = seedCampaign(List.of(
                new NurtureSegmentDefinition(DormancyBucket.A, 30, 90, null, null),
                new NurtureSegmentDefinition(DormancyBucket.B, 90, null, null, null)));

        NurtureSegmentationService.SegmentationResult r1 =
                asTenant(segmentation.segmentAndEnroll(tenantId, campaign.getId()));

        assertThat(r1.matched()).isEqualTo(2);
        assertThat(r1.enrolled()).isEqualTo(2);
        assertThat(r1.skippedOptedOut()).isEqualTo(1);
        assertThat(r1.alreadyEnrolled()).isZero();

        List<NurtureEnrollment> all = mongo.findAll(NurtureEnrollment.class).collectList().block();
        assertThat(all).hasSize(2);
        assertThat(all).noneMatch(e -> e.getContactId().equals(optedOut));
        assertThat(all).filteredOn(e -> e.getContactId().equals(aContact))
                .singleElement()
                .satisfies(e -> {
                    assertThat(e.getBucket()).isEqualTo(DormancyBucket.A);
                    assertThat(e.getStatus()).isEqualTo(NurtureEnrollmentStatus.ENROLLED);
                    assertThat(e.getNextFireAt()).isEqualTo(NOW);
                });
        assertThat(all).filteredOn(e -> e.getContactId().equals(bContact))
                .singleElement()
                .satisfies(e -> assertThat(e.getBucket()).isEqualTo(DormancyBucket.B));

        // Re-run: idempotent — no duplicate enrollment (the unique-index + explicit-boolean backstop).
        NurtureSegmentationService.SegmentationResult r2 =
                asTenant(segmentation.segmentAndEnroll(tenantId, campaign.getId()));
        assertThat(r2.enrolled()).isZero();
        assertThat(r2.alreadyEnrolled()).isEqualTo(2);
        assertThat(mongo.findAll(NurtureEnrollment.class).collectList().block()).hasSize(2);
    }

    @Test
    void valueBandSegment_enrollsOnlyHighValueContacts() {
        UUID highValue = seedContact("Whale", 120L, null);
        UUID lowValue = seedContact("Minnow", 120L, null);
        // A WON deal of $10k for the high-value contact; the low-value contact has none.
        mongo.save(Deal.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).title("Big WON")
                .stage(PipelineStage.WON).value(new BigDecimal("10000"))
                .primaryContactId(highValue)
                .build()).block();

        // Single bucket: dormant >= 90 days AND lifetime value >= $5000.
        NurtureCampaign campaign = seedCampaign(List.of(
                new NurtureSegmentDefinition(
                        DormancyBucket.A, 90, null, new BigDecimal("5000"), null)));

        NurtureSegmentationService.SegmentationResult r =
                asTenant(segmentation.segmentAndEnroll(tenantId, campaign.getId()));

        assertThat(r.matched()).isEqualTo(1);
        assertThat(r.enrolled()).isEqualTo(1);
        List<NurtureEnrollment> all = mongo.findAll(NurtureEnrollment.class).collectList().block();
        assertThat(all).singleElement()
                .satisfies(e -> assertThat(e.getContactId()).isEqualTo(highValue));
        assertThat(all).noneMatch(e -> e.getContactId().equals(lowValue));
    }

    @Test
    void inactiveCampaign_errors4302() {
        NurtureCampaign campaign = mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Paused").active(false)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.A, 0, null, null, null)))
                .build()).block();

        Throwable t = catchThrow(() -> asTenant(segmentation.segmentAndEnroll(tenantId, campaign.getId())));
        assertThat(t).isNotNull();
        assertThat(t.getMessage()).contains("inactive");
    }

    private static Throwable catchThrow(Runnable r) {
        try {
            r.run();
            return null;
        } catch (Throwable t) {
            return t;
        }
    }
}
