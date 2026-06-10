package com.kumouri.kmodigipresbe.nurture;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * THE GATE-2 regression IT — proves the vertical-scoped copy filter is correct when <strong>both</strong>
 * verticals are live in one process.
 *
 * <p>Before the fix {@code NurtureMessageComposer} held a single last-wins {@code @Nullable
 * NurtureCopyFilter}; with both {@code realestate+nurture} and {@code frontdesk+nurture} enabled, the
 * second autoconfig's {@code setCopyFilter} clobbered the first — so one vertical's messages got the wrong
 * filter or none (worst case a health message shipping without the HIPAA screen). After the fix the
 * composer holds a registry of filters and dispatches by the <em>campaign's</em> {@code vertical}, so each
 * vertical is screened by its own filter and neither collides.
 *
 * <p>This IT enables BOTH modules in one Spring context (so BOTH {@code FairHousingCopyFilter} and
 * {@code HipaaCopyFilter} are registered) and proves, in a single {@code runDueOnce()} sweep:
 * <ul>
 *   <li>a non-compliant RE campaign (vertical {@code "realestate"}, "perfect for families ... safe
 *       neighborhood") → the Fair-Housing <strong>safe RE fallback</strong> is sent (zero RE-banned text);</li>
 *   <li>a PHI-ish health campaign (vertical {@code "health"}, "your crown") → the HIPAA <strong>safe
 *       generic fallback</strong> is sent (zero clinical text);</li>
 *   <li>the RE message did NOT receive the health fallback and vice-versa — neither collides, neither is
 *       skipped (the headline);</li>
 *   <li>a clean RE campaign and a clean health campaign are each sent verbatim (the right filter passed
 *       compliant copy through, did not over-substitute);</li>
 *   <li>a <strong>null-vertical (legacy)</strong> campaign carrying non-compliant copy is sent
 *       <strong>unfiltered</strong> — the documented safe legacy behavior (an untagged campaign never gets
 *       a vertical-specific screen, and a mis-tag can never silently apply the wrong vertical's filter).</li>
 * </ul>
 *
 * <p>Mirrors {@code RealEstateNurtureFairHousingIT} / {@code FrontDeskNurturePhiSafeIT} (default-OFF runner
 * enabled in-test, {@code @MockitoBean} TwilioSmsService, deterministic {@code runDueOnce()} block,
 * scheduled tick pushed far out) but enables {@code realestate} AND {@code frontdesk} together. AI is OFF
 * on every step here ({@code aiPersonalize=false}) — the filter dispatch, not the AI path, is under test;
 * the per-vertical AI-rewrite screen is already proven by the two single-vertical headline ITs. §7: no live
 * send — the runner is default-OFF in prod/CI; this IT opts in AND mocks the send seam.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, BothVerticalsNurtureCopyFilterIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // BOTH verticals enabled in one context — the whole point of this regression.
        "kmosf.modules.realestate.enabled=true",
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.modules.nurture-runner.enabled=true",
        "kmosf.modules.nurture-runner.initial-delay-ms=3600000",
        "kmosf.modules.nurture-runner.interval-ms=3600000",
        "kmosf.mail.smtp.username=nurture@both.test",
        // Deterministic per-vertical safe fallbacks so the assertions are exact + mutually distinguishable.
        "kmosf.realestate.nurture.safe-sms=Hi {firstName}, checking in from Gateway Realty — reply YES to chat.",
        "kmosf.realestate.nurture.safe-email-body=Hi {firstName}, checking in from Gateway Realty.",
        "kmosf.frontdesk.nurture.safe-sms=Hi {firstName}, checking in from Bright Smiles — reply YES to get back on the schedule.",
        "kmosf.frontdesk.nurture.safe-email-body=Hi {firstName}, checking in from Bright Smiles."
})
class BothVerticalsNurtureCopyFilterIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");

    private static final String RE_PHONE = "+12145550001";
    private static final String HEALTH_PHONE = "+13145550001";
    private static final String LEGACY_PHONE = "+12145550999";

    private static final String RE_SAFE_SMS =
            "Hi Re, checking in from Gateway Realty — reply YES to chat.";
    private static final String HEALTH_SAFE_SMS =
            "Hi Doc, checking in from Bright Smiles — reply YES to get back on the schedule.";

    static WireMockServer wireMock;

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

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
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsBodies.clear();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsBodies.add(req.body());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("both-verticals-it-" + tenantId)
                .displayName("Both Verticals IT").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new java.math.BigDecimal("5.00"))
                .build()).block();
    }

    private UUID seedContact(String first, String phone) {
        UUID cid = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(cid).tenantId(tenantId).type(ContactType.PERSON)
                .firstName(first).displayName(first)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(Set.of())
                .build()).block();
        return cid;
    }

    /** One SMS step-0 campaign tagged with the given vertical (no AI — the dispatch, not the AI path, is tested). */
    private NurtureCampaign seedCampaign(String name, String vertical, String smsTemplate) {
        return mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name(name).vertical(vertical).active(true)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.A, 0, null, null, null)))
                .steps(List.of(new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                        smsTemplate, null, null, false, 0)))
                .maxTouchesPerContactPerWindow(5)
                .build()).block();
    }

    private void seedEnrollment(UUID campaignId, UUID contactId) {
        mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).contactId(contactId)
                .bucket(DormancyBucket.A)
                .currentStepIndex(0)
                .status(NurtureEnrollmentStatus.ENROLLED)
                .nextFireAt(NOW.minusSeconds(60))
                .build()).block();
    }

    @Test
    void bothVerticals_eachScreenedByItsOwnFilter_neitherCollides() {
        // A non-compliant RE campaign (Fair-Housing steering language) tagged vertical=realestate.
        NurtureCampaign re = seedCampaign("RE Reactivation", "realestate",
                "Hi {firstName}, this home is perfect for families in a safe neighborhood!");
        seedEnrollment(re.getId(), seedContact("Re", RE_PHONE));

        // A PHI-ish health campaign (clinical procedure) tagged vertical=health.
        NurtureCampaign health = seedCampaign("Health Reactivation", "health",
                "Hi {firstName}, you're overdue for your crown — let's get you scheduled!");
        seedEnrollment(health.getId(), seedContact("Doc", HEALTH_PHONE));

        runner.runDueOnce().block();

        // Both sent (never dropped), and each got ITS OWN vertical's safe fallback.
        assertThat(smsBodies).containsExactlyInAnyOrder(RE_SAFE_SMS, HEALTH_SAFE_SMS);

        // The RE message went through the Fair-Housing filter -> RE-safe body, zero RE-banned text.
        assertThat(smsBodies).contains(RE_SAFE_SMS);
        assertThat(smsBodies.stream().anyMatch(b -> b.toLowerCase().contains("perfect for families"))).isFalse();
        assertThat(smsBodies.stream().anyMatch(b -> b.toLowerCase().contains("safe neighborhood"))).isFalse();

        // The health message went through the HIPAA filter -> generic-safe body, zero clinical text.
        assertThat(smsBodies).contains(HEALTH_SAFE_SMS);
        assertThat(smsBodies.stream().anyMatch(b -> b.toLowerCase().contains("crown"))).isFalse();

        // The headline: NO collision — the RE body is not the health fallback, the health body is not the RE one.
        assertThat(RE_SAFE_SMS).doesNotContain("Bright Smiles");
        assertThat(HEALTH_SAFE_SMS).doesNotContain("Gateway Realty");

        // Two ledger rows — neither send was skipped.
        assertThat(mongo.findAll(NurtureSendLog.class).collectList().block()).hasSize(2);
    }

    @Test
    void bothVerticals_cleanCopy_eachSentVerbatim_noOverSubstitution() {
        NurtureCampaign re = seedCampaign("RE Clean", "realestate",
                "Hi {firstName}, the market's moved — want a quick value update?");
        seedEnrollment(re.getId(), seedContact("Re", RE_PHONE));

        NurtureCampaign health = seedCampaign("Health Clean", "health",
                "Hi {firstName}, it's been a while — want to get back on the schedule?");
        seedEnrollment(health.getId(), seedContact("Doc", HEALTH_PHONE));

        runner.runDueOnce().block();

        // Each clean message passed its own filter untouched (the matching filter did NOT over-substitute).
        assertThat(smsBodies).containsExactlyInAnyOrder(
                "Hi Re, the market's moved — want a quick value update?",
                "Hi Doc, it's been a while — want to get back on the schedule?");
    }

    @Test
    void nullVerticalCampaign_nonCompliantCopy_isSentUnfiltered_safeLegacyBehavior() {
        // A legacy/untagged campaign (vertical=null) with copy that WOULD be caught by a vertical filter.
        // The safe invariant: an untagged campaign gets NO vertical-specific screen (it is sent as-is) —
        // we never guess a vertical and risk applying the WRONG vertical's substitution.
        NurtureCampaign legacy = seedCampaign("Legacy Untagged", null,
                "Hi {firstName}, this home is perfect for families!");
        seedEnrollment(legacy.getId(), seedContact("Leg", LEGACY_PHONE));

        runner.runDueOnce().block();

        // Sent unchanged — byte-identical to pre-T1 E1 (no filter applied because no vertical matched).
        assertThat(smsBodies).singleElement()
                .isEqualTo("Hi Leg, this home is perfect for families!");
    }
}
