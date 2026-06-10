package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
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
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T2 — the HEADLINE IT: the PHI-free guardrail on every health nurture outbound message (the health twin of
 * T1's {@code RealEstateNurtureFairHousingIT}). With the frontdesk + nurture modules both on,
 * {@code FrontDeskNurtureAutoConfiguration} wires {@code HipaaCopyFilter} onto the shared
 * {@code NurtureMessageComposer}, so the default-OFF {@code NurtureRunner} (enabled in-test) screens every
 * send.
 *
 * <p>Proves:
 * <ul>
 *   <li>a clean health template is sent verbatim;</li>
 *   <li>a campaign step with a <strong>PHI-ish template</strong> ("your crown") → the SMS actually sent is the
 *       vetted <strong>safe generic fallback</strong> (zero clinical text);</li>
 *   <li>{@code aiPersonalize=true} + WireMock Anthropic returns a <strong>PHI-ish rewrite</strong> ("thanks
 *       for being our patient — your procedure went great") → the lint catches the AI draft → the safe
 *       fallback is sent (proves the screen covers the AI-personalized path, not just the template);</li>
 *   <li>an opted-out contact → zero send (the engine's TCPA skip, unchanged).</li>
 * </ul>
 *
 * <p>Mirrors {@code RealEstateNurtureFairHousingIT} (default-OFF runner enabled in-test, {@code @MockitoBean}
 * TwilioSmsService, deterministic {@code runDueOnce()} block, scheduled tick pushed far out, Anthropic →
 * WireMock via {@code @DynamicPropertySource}) + adds {@code kmosf.modules.frontdesk.enabled=true} so the
 * filter is wired. §7: no live send anywhere — the runner is default-OFF in prod/CI; this IT opts in AND
 * mocks the send seam.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, FrontDeskNurturePhiSafeIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.modules.nurture-runner.enabled=true",
        "kmosf.modules.nurture-runner.initial-delay-ms=3600000",
        "kmosf.modules.nurture-runner.interval-ms=3600000",
        "kmosf.mail.smtp.username=nurture@bright-smiles.test",
        // Deterministic safe fallbacks so the assertions are exact.
        "kmosf.frontdesk.nurture.safe-sms=Hi {firstName}, checking in from Bright Smiles — reply YES to get back on the schedule.",
        "kmosf.frontdesk.nurture.safe-email-body=Hi {firstName}, checking in from Bright Smiles."
})
class FrontDeskNurturePhiSafeIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final String PHONE = "+13145550123";
    private static final String SAFE_SMS =
            "Hi Pat, checking in from Bright Smiles — reply YES to get back on the schedule.";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-fd-nurture-fake";

    static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void anthropicProps(DynamicPropertyRegistry registry) {
        registry.add("kmosf.ai.anthropic.base-url", () -> wireMock.baseUrl());
    }

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
    @Autowired NurtureEnrollmentRepository enrollments;
    @Autowired IntegrationConnectionRepository connections;

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
        wireMock.resetAll();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsBodies.add(req.body());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("fd-nurture-phi-it-" + tenantId)
                .displayName("Bright Smiles PHI IT").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new java.math.BigDecimal("5.00"))
                .build()).block();
    }

    private void stubAnthropic(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                                + "\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}")));
    }

    private UUID seedContact(String name, Set<String> tags) {
        UUID cid = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(cid).tenantId(tenantId).type(ContactType.PERSON)
                .firstName(name).displayName(name)
                .phones(List.of(PhoneNumber.builder().number(PHONE).label("mobile").build()))
                .tags(tags == null ? Set.of() : tags)
                .build()).block();
        return cid;
    }

    /** One SMS step 0, with the given template + aiPersonalize flag (a sweep-all A-bucket segment). */
    private NurtureCampaign seedCampaign(String smsTemplate, boolean aiPersonalize) {
        return mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                // GATE-2: campaigns are vertical-tagged so the composer dispatches the HIPAA filter.
                .name("Health Reactivation").vertical(HipaaCopyFilter.VERTICAL).active(true)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.A, 0, null, null, null)))
                .steps(List.of(new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                        smsTemplate, null, null, aiPersonalize, 0)))
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
    void cleanTemplate_isSentVerbatim() {
        NurtureCampaign campaign = seedCampaign(
                "Hi {firstName}, it's been a while — want to get back on the schedule?", false);
        seedEnrollment(campaign.getId(), seedContact("Pat", null));

        runner.runDueOnce().block();

        assertThat(smsBodies).singleElement()
                .isEqualTo("Hi Pat, it's been a while — want to get back on the schedule?");
    }

    @Test
    void phiIshTemplate_isReplacedWithSafeFallback() {
        // A campaign template naming a clinical procedure ("crown") — a PHI disclosure; must NOT be sent.
        NurtureCampaign campaign = seedCampaign(
                "Hi {firstName}, you're overdue for your crown — let's get you scheduled!", false);
        seedEnrollment(campaign.getId(), seedContact("Pat", null));

        runner.runDueOnce().block();

        // The lint caught it → the vetted safe fallback was sent; zero clinical text.
        assertThat(smsBodies).singleElement().isEqualTo(SAFE_SMS);
        assertThat(smsBodies.get(0).toLowerCase()).doesNotContain("crown");
        // The send still happened (never silently dropped) + one ledger row exists.
        assertThat(mongo.findAll(NurtureSendLog.class).collectList().block()).hasSize(1);
    }

    @Test
    void phiIshAiRewrite_isCaughtByLint_safeFallbackSent() {
        // The template is clean, but the AI rewrite injects patient-status confirmation + a procedure — the
        // lint must catch the AI draft (proves the screen covers the AI-personalized path, not just template).
        stubAnthropic("Thanks for being our patient! Your procedure went great — time for a cleaning.");
        connections.save(IntegrationConnection.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        NurtureCampaign campaign = seedCampaign(
                "Hi {firstName}, it's been a while — want to get back on the schedule?", true);
        seedEnrollment(campaign.getId(), seedContact("Pat", null));

        runner.runDueOnce().block();

        assertThat(smsBodies).singleElement().isEqualTo(SAFE_SMS);
        assertThat(smsBodies.get(0).toLowerCase()).doesNotContain("being our patient");
        assertThat(smsBodies.get(0).toLowerCase()).doesNotContain("procedure");
        assertThat(smsBodies.get(0).toLowerCase()).doesNotContain("cleaning");
    }

    @Test
    void optedOutContact_isSkipped_zeroSend() {
        NurtureCampaign campaign = seedCampaign("Hi {firstName}, time for your crown!", false);
        seedEnrollment(campaign.getId(),
                seedContact("NoText", Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG)));

        runner.runDueOnce().block();

        assertThat(smsBodies).isEmpty();
        assertThat(mongo.findAll(NurtureSendLog.class).collectList().block()).isEmpty();
    }
}
