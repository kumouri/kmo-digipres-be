package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
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
import com.kumouri.kmodigipresbe.model.nurture.NurtureSendLog;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.service.nurture.NurtureRunner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T11 (Home "QuoteCloser") — <strong>the GATE-2 third-vertical proof.</strong> The QuoteCloser cadence is a
 * {@code vertical="home"} campaign; home has NO registered {@link com.kumouri.kmodigipresbe.service.nurture.NurtureCopyFilter}
 * (only realestate=Fair-Housing and health=HIPAA are registered). This IT proves that a home cadence's copy is
 * sent <strong>UNFILTERED</strong> (verbatim template — no Fair-Housing / HIPAA substitution) even when BOTH
 * the realestate and frontdesk filters are registered in the same Spring context — confirming the GATE-2
 * vertical-scoped dispatch is correct for a third vertical (the {@code BothVerticalsNurtureCopyFilterIT}
 * companion, here for the un-tagged-by-any-registered-filter "home" case).
 *
 * <p>It enables {@code realestate} AND {@code frontdesk} (so BOTH the Fair-Housing AND HIPAA copy filters are
 * registered on the composer) AND the default-OFF {@code NurtureRunner}, mocks the SMS seam, seeds a
 * {@code vertical="home"} QuoteCloser campaign carrying copy that a Fair-Housing OR a HIPAA filter WOULD catch
 * if it were applied, and asserts the body is sent <strong>verbatim</strong> — proving no vertical filter
 * touched it. §7: no live send — the runner is default-OFF in prod/CI; this IT opts in AND mocks the seam.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.quoting.enabled=true",
        "kmosf.modules.nurture.enabled=true",
        // BOTH filter-registering verticals on in one context — the whole point of the third-vertical proof.
        "kmosf.modules.realestate.enabled=true",
        "kmosf.modules.frontdesk.enabled=true",
        // The default-OFF runner, opted in here; tick pushed far out (the IT drives runDueOnce()).
        "kmosf.modules.nurture-runner.enabled=true",
        "kmosf.modules.nurture-runner.initial-delay-ms=3600000",
        "kmosf.modules.nurture-runner.interval-ms=3600000",
        "kmosf.mail.smtp.username=quotecloser@home.test",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class QuoteCloserUnfilteredCopyIT {

    // Copy that the Fair-Housing filter ("families"/"safe neighborhood") AND the HIPAA filter would flag if
    // either were (incorrectly) applied to a home campaign — proving neither touches a vertical="home" send.
    private static final String HOME_SMS =
            "Hi Jordan, this home upgrade is perfect for families in a safe neighborhood — "
                    + "and your crown estimate is ready. Reply to book.";
    private static final String HOME_PHONE = "+13145550900";

    @Autowired ReactiveMongoTemplate mongo;
    @Autowired NurtureRunner runner;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<String> smsBodies = new CopyOnWriteArrayList<>();

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureSendLog.class).block();
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsBodies.clear();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsBodies.add(req.body());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("quote-closer-home-copy-it-" + tenantId)
                .displayName("QuoteCloser Home Copy IT").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
    }

    @Test
    void homeVerticalCadenceCopy_isSentUnfiltered_evenWithReAndHealthFiltersRegistered() {
        // A vertical="home" QuoteCloser campaign with copy a Fair-Housing/HIPAA filter WOULD catch.
        UUID campaignId = UUID.randomUUID();
        mongo.save(NurtureCampaign.builder()
                .id(campaignId).tenantId(tenantId)
                .name("QuoteCloser").vertical("home").active(true)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.A, 0, null, null, null)))
                .steps(List.of(new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                        HOME_SMS, null, null, false, 0)))
                .maxTouchesPerContactPerWindow(5)
                .build()).block();

        UUID contactId = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(contactId).tenantId(tenantId).type(ContactType.PERSON)
                .firstName("Jordan").displayName("Jordan Ellis")
                .phones(List.of(PhoneNumber.builder().number(HOME_PHONE).label("mobile").build()))
                .tags(Set.of())
                .build()).block();

        mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).contactId(contactId)
                .bucket(DormancyBucket.A).currentStepIndex(0)
                .status(NurtureEnrollmentStatus.ENROLLED)
                .nextFireAt(Instant.now().minusSeconds(60))
                .build()).block();

        runner.runDueOnce().block();

        // The home cadence copy was sent VERBATIM — no Fair-Housing / HIPAA substitution applied.
        assertThat(smsBodies).singleElement().isEqualTo(HOME_SMS);
        // Defensive: the would-be-flagged phrases are STILL PRESENT (proving no filter replaced the body).
        assertThat(smsBodies.get(0)).contains("perfect for families");
        assertThat(smsBodies.get(0)).contains("safe neighborhood");
        assertThat(smsBodies.get(0)).contains("crown");
    }
}
