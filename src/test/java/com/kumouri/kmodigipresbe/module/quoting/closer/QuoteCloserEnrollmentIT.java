package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.TestPropertySource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T11 (Home "QuoteCloser") — {@link QuoteCloserEnrollmentJob}: the default-OFF enrollment + stop sweep.
 * Drives the visible-for-test {@link QuoteCloserEnrollmentJob#sweepDueOnce()} deterministically.
 *
 * <p>Proves:
 * <ul>
 *   <li>a {@code NEW} quote aged past the window → its contact is enrolled in the QuoteCloser campaign
 *       (status ENROLLED, the right campaign), and a second sweep does NOT double-enroll (idempotent);</li>
 *   <li>a {@code NEW} quote that is NOT yet aged → not enrolled;</li>
 *   <li>a {@code NEW} quote with no contact → skipped;</li>
 *   <li>the stop leg: an active QuoteCloser enrollment whose quote went non-{@code NEW} (e.g. office-set
 *       ACCEPTED) is EXITED by the sweep.</li>
 * </ul>
 *
 * <p>The {@code @CreatedDate}-managed {@code QuoteRequest.createdAt} is stamped to "now" by Spring Data
 * auditing on insert, so an "aged" quote is backdated via a post-save {@code ReactiveMongoTemplate} update
 * (the only way to set a controlled {@code createdAt}). §7: no live send — the job only enrolls (the
 * default-OFF {@code NurtureRunner} is the sender and is not enabled here).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.modules.nurture.enabled=true",
        // Create the QuoteCloserEnrollmentJob bean (default-OFF in prod/CI).
        "kmosf.modules.quote-closer-job.enabled=true",
        // Keep the sweep's scheduled tick far out — the IT drives sweepDueOnce() directly.
        "kmosf.modules.quote-closer-job.initial-delay-ms=3600000",
        "kmosf.modules.quote-closer-job.interval-ms=3600000",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteCloserEnrollmentIT {

    private static final int WINDOW_HOURS = 48;

    @Autowired QuoteCloserEnrollmentJob job;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private UUID campaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), QuoteRequest.class).block();
        mongo.remove(new Query(), QuoteCloserConfig.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("quote-closer-enroll-it-" + tenantId)
                .displayName("QuoteCloser Enroll IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("quoting", "nurture")).aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();

        campaignId = UUID.randomUUID();
        mongo.save(NurtureCampaign.builder()
                .id(campaignId).tenantId(tenantId)
                .name("QuoteCloser").vertical("home").active(true)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.A, 0, null, null, null)))
                .steps(List.of(new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                        "Just checking in on your estimate.", null, null, false, 0)))
                .maxTouchesPerContactPerWindow(5)
                .build()).block();

        mongo.save(QuoteCloserConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).unacceptedWindowHours(WINDOW_HOURS)
                .build()).block();
    }

    private UUID seedContact(String phone) {
        UUID cid = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(cid).tenantId(tenantId).type(ContactType.PERSON)
                .firstName("Jordan").displayName("Jordan Ellis")
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(Set.of())
                .build()).block();
        return cid;
    }

    /** Save a quote then (optionally) backdate its createdAt via a template update (auditing stamps now). */
    private UUID seedQuote(UUID contactId, QuoteStatus status, long ageHours) {
        UUID qid = UUID.randomUUID();
        mongo.save(QuoteRequest.builder()
                .id(qid).tenantId(tenantId)
                .contactId(contactId)
                .contactPhone("+13145550000")
                .status(status)
                .build()).block();
        if (ageHours > 0) {
            Instant created = Instant.now().minus(ageHours, ChronoUnit.HOURS);
            mongo.updateFirst(new Query(Criteria.where("_id").is(qid)),
                    new Update().set("createdAt", created), QuoteRequest.class).block();
        }
        return qid;
    }

    private NurtureEnrollment enrollmentFor(UUID contactId) {
        return mongo.find(new Query(Criteria.where("tenantId").is(tenantId)
                                .and("campaignId").is(campaignId)
                                .and("contactId").is(contactId)),
                        NurtureEnrollment.class)
                .blockFirst();
    }

    @Test
    void agedNewQuote_enrollsContact_inTheRightCampaign() {
        UUID contactId = seedContact("+13145550101");
        seedQuote(contactId, QuoteStatus.NEW, 72); // older than the 48h window

        job.sweepDueOnce().block();

        NurtureEnrollment enr = enrollmentFor(contactId);
        assertThat(enr).as("aged NEW quote → contact enrolled").isNotNull();
        assertThat(enr.getCampaignId()).isEqualTo(campaignId);
        assertThat(enr.getStatus()).isEqualTo(NurtureEnrollmentStatus.ENROLLED);
        assertThat(enr.getNextFireAt()).as("due immediately").isNotNull();
    }

    @Test
    void secondSweep_doesNotDoubleEnroll_idempotent() {
        UUID contactId = seedContact("+13145550102");
        seedQuote(contactId, QuoteStatus.NEW, 72);

        job.sweepDueOnce().block();
        job.sweepDueOnce().block();

        Long count = mongo.count(new Query(Criteria.where("tenantId").is(tenantId)
                        .and("campaignId").is(campaignId).and("contactId").is(contactId)),
                NurtureEnrollment.class).block();
        assertThat(count).as("idempotent — exactly one enrollment after two sweeps").isEqualTo(1L);
    }

    @Test
    void notYetAgedNewQuote_isNotEnrolled() {
        UUID contactId = seedContact("+13145550103");
        seedQuote(contactId, QuoteStatus.NEW, 0); // created "now" — inside the 48h window

        job.sweepDueOnce().block();

        assertThat(enrollmentFor(contactId)).as("a fresh NEW quote is not yet aged → no enroll").isNull();
    }

    @Test
    void newQuoteWithNoContact_isSkipped() {
        // A quote with no contactId, aged.
        UUID qid = UUID.randomUUID();
        mongo.save(QuoteRequest.builder()
                .id(qid).tenantId(tenantId).status(QuoteStatus.NEW)
                .build()).block();
        mongo.updateFirst(new Query(Criteria.where("_id").is(qid)),
                new Update().set("createdAt", Instant.now().minus(72, ChronoUnit.HOURS)),
                QuoteRequest.class).block();

        job.sweepDueOnce().block();

        Long enrollments = mongo.count(new Query(Criteria.where("tenantId").is(tenantId)),
                NurtureEnrollment.class).block();
        assertThat(enrollments).as("a quote with no contact creates no enrollment").isEqualTo(0L);
    }

    @Test
    void stopLeg_enrollmentExitedWhenQuoteNoLongerNew() {
        // A contact enrolled in the QuoteCloser campaign whose only quote is now ACCEPTED (office-set,
        // no QUOTE_ACCEPTED event) → the sweep's stop leg exits the enrollment.
        UUID contactId = seedContact("+13145550104");
        seedQuote(contactId, QuoteStatus.ACCEPTED, 0); // not NEW
        mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).contactId(contactId)
                .bucket(DormancyBucket.A).currentStepIndex(0)
                .status(NurtureEnrollmentStatus.ACTIVE)
                .nextFireAt(Instant.now())
                .build()).block();

        job.sweepDueOnce().block();

        NurtureEnrollment enr = enrollmentFor(contactId);
        assertThat(enr).isNotNull();
        assertThat(enr.getStatus()).as("quote no longer NEW → cadence stopped (EXITED)")
                .isEqualTo(NurtureEnrollmentStatus.EXITED);
        assertThat(enr.getExitedReason()).isEqualTo("quote no longer open");
    }
}
