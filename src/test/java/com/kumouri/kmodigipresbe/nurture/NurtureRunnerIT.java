package com.kumouri.kmodigipresbe.nurture;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
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
import com.kumouri.kmodigipresbe.repository.nurture.NurtureSendLogRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureRunner;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
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
import reactor.core.Disposable;
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
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * E1 — NurtureRunnerIT: the keystone. Drives the default-OFF {@code NurtureRunner} (enabled in-test)
 * and proves: a due enrollment sends ONE SMS + writes ONE {@code NurtureSendLog(step 0)} + fires
 * {@code NURTURE_TOUCH_SENT} + advances; a second sweep in the same tick sends ZERO duplicate; an
 * opted-out contact is skipped ({@code OPTED_OUT}); an inactive campaign exits the enrollment; and the
 * AI-personalize path hits WireMock Anthropic (with a templated fallback when the upstream errors).
 *
 * <p>Mirrors {@code CoverageNudgeIT} (default-OFF runner enabled in-test, {@code @MockitoBean}
 * TwilioSmsService + EmailService, deterministic {@code runDueOnce()} block, scheduled tick pushed far
 * out) + {@code MoleTriageIT} (Anthropic → WireMock via {@code @DynamicPropertySource}). §7: no live
 * send anywhere — the runner is default-OFF in prod/CI; this IT opts in AND mocks the send seams.
 */
@SpringBootTest
@Import({TestcontainersConfiguration.class, NurtureRunnerIT.FixedClockConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.nurture-runner.enabled=true",
        // Push the @Scheduled tick far out so only the explicit runDueOnce() runs in-test.
        "kmosf.modules.nurture-runner.initial-delay-ms=3600000",
        "kmosf.modules.nurture-runner.interval-ms=3600000",
        "kmosf.mail.smtp.username=nurture@kmosf.test"
})
class NurtureRunnerIT {

    private static final Instant NOW = Instant.parse("2026-06-09T12:00:00Z");
    private static final String PHONE = "+16185550123";
    private static final String ANTHROPIC_API_KEY = "sk-ant-test-nurture-fake";

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
    @Autowired NurtureSendLogRepository sendLogs;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final List<String> smsBodies = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureSendLog.class).block();
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsTo.clear();
        smsBodies.clear();
        wireMock.resetAll();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            smsBodies.add(req.body());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("nurture-runner-it-" + tenantId)
                .displayName("Nurture Runner IT").status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(new java.math.BigDecimal("5.00")) // non-zero so the AI budget gate passes
                .build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    private void stubAnthropic(String text) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + text + "\"}],"
                                + "\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}")));
    }

    private void stubAnthropic500() {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("boom")));
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

    private NurtureCampaign seedCampaign(boolean active, boolean aiPersonalize) {
        return mongo.save(NurtureCampaign.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .name("Reactivation").active(active)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.A, 0, null, null, null)))
                .steps(List.of(
                        new NurtureCadenceStep(0, NurtureChannel.SMS, 0,
                                "Hi {firstName}, still need a hand?", null, null, aiPersonalize, 0),
                        new NurtureCadenceStep(1, NurtureChannel.SMS, 3,
                                "Following up {firstName}!", null, null, false, 0)))
                .maxTouchesPerContactPerWindow(5)
                .build()).block();
    }

    private NurtureEnrollment seedEnrollment(UUID campaignId, UUID contactId) {
        return mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).contactId(contactId)
                .bucket(DormancyBucket.A)
                .currentStepIndex(0)
                .status(NurtureEnrollmentStatus.ENROLLED)
                .nextFireAt(NOW.minusSeconds(60)) // due
                .build()).block();
    }

    @Test
    void sendsOneTouch_writesLedger_advances_andIsIdempotentPerStep() {
        NurtureCampaign campaign = seedCampaign(true, false);
        UUID contact = seedContact("Pat", null);
        NurtureEnrollment enr = seedEnrollment(campaign.getId(), contact);

        runner.runDueOnce().block();

        // Exactly one SMS, with the rendered template.
        assertThat(smsTo).containsExactly(PHONE);
        assertThat(smsBodies).singleElement().isEqualTo("Hi Pat, still need a hand?");

        // One ledger row for step 0.
        List<NurtureSendLog> logs = mongo.findAll(NurtureSendLog.class).collectList().block();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getStepIndex()).isZero();
        assertThat(logs.get(0).getEnrollmentId()).isEqualTo(enr.getId());

        // Advanced to step 1, ACTIVE, future nextFireAt (offsetDays=3).
        NurtureEnrollment after = enrollments.findByTenantIdAndId(tenantId, enr.getId()).block();
        assertThat(after.getCurrentStepIndex()).isEqualTo(1);
        assertThat(after.getStatus()).isEqualTo(NurtureEnrollmentStatus.ACTIVE);
        assertThat(after.getNextFireAt()).isEqualTo(NOW.plus(3, java.time.temporal.ChronoUnit.DAYS));

        // The advisory event fired once.
        assertThat(observed).filteredOn(e -> DomainEventType.NURTURE_TOUCH_SENT.equals(e.type()))
                .hasSize(1);

        // Second sweep in the same period — step 1 is NOT due yet (nextFireAt is 3d out), so ZERO
        // additional send and still exactly one ledger row.
        runner.runDueOnce().block();
        assertThat(smsTo).containsExactly(PHONE);
        assertThat(mongo.findAll(NurtureSendLog.class).collectList().block()).hasSize(1);
    }

    @Test
    void duplicateSweepOfTheSameDueStep_sendsZeroDuplicate() {
        // Force step 1 to also be immediately due (offsetDays 0 backoff 0) by making BOTH steps due:
        // we keep the enrollment on step 0 but run two concurrent-style sequential sweeps. The first
        // advances to step 1 with a future fire; to test the ledger guard on the SAME step we instead
        // re-point the enrollment back to step 0 after the first send and re-run — the ledger row for
        // step 0 already exists, so the second attempt is a zero-duplicate no-op.
        NurtureCampaign campaign = seedCampaign(true, false);
        UUID contact = seedContact("Sam", null);
        NurtureEnrollment enr = seedEnrollment(campaign.getId(), contact);

        runner.runDueOnce().block();
        assertThat(smsTo).hasSize(1);

        // Re-arm the SAME step 0 as due (simulating a misfire/restart that re-presents step 0).
        // Re-load first so we carry the post-run @Version (avoid a stale-version optimistic-lock error
        // — the runner already bumped it when it advanced).
        NurtureEnrollment current = enrollments.findByTenantIdAndId(tenantId, enr.getId()).block();
        enrollments.save(current.toBuilder()
                .currentStepIndex(0)
                .status(NurtureEnrollmentStatus.ENROLLED)
                .nextFireAt(NOW.minusSeconds(60))
                .build()).block();

        runner.runDueOnce().block();

        // The unique tenant_enrollment_step_idx made the second step-0 attempt a zero-duplicate no-op.
        assertThat(smsTo).hasSize(1);
        assertThat(mongo.findAll(NurtureSendLog.class).collectList().block()).hasSize(1);
    }

    @Test
    void optedOutContact_isSkipped_optedOutStatus_zeroSend() {
        NurtureCampaign campaign = seedCampaign(true, false);
        UUID contact = seedContact("NoText", Set.of(RiskTieredPreventionService.SMS_OPT_OUT_TAG));
        NurtureEnrollment enr = seedEnrollment(campaign.getId(), contact);

        runner.runDueOnce().block();

        assertThat(smsTo).isEmpty();
        assertThat(mongo.findAll(NurtureSendLog.class).collectList().block()).isEmpty();
        NurtureEnrollment after = enrollments.findByTenantIdAndId(tenantId, enr.getId()).block();
        assertThat(after.getStatus()).isEqualTo(NurtureEnrollmentStatus.OPTED_OUT);
    }

    @Test
    void inactiveCampaign_exitsEnrollment_zeroSend() {
        NurtureCampaign campaign = seedCampaign(false, false);
        UUID contact = seedContact("Lee", null);
        NurtureEnrollment enr = seedEnrollment(campaign.getId(), contact);

        runner.runDueOnce().block();

        assertThat(smsTo).isEmpty();
        NurtureEnrollment after = enrollments.findByTenantIdAndId(tenantId, enr.getId()).block();
        assertThat(after.getStatus()).isEqualTo(NurtureEnrollmentStatus.EXITED);
        assertThat(after.getExitedReason()).contains("inactive");
    }

    @Test
    void aiPersonalize_hitsWireMockAnthropic_andUsesRewrittenCopy() {
        stubAnthropic("Hey Pat! Still keen to get that sorted?");
        // Tenant has its own Anthropic key (resolveKey path), so no house key needed.
        connections.save(IntegrationConnection.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        NurtureCampaign campaign = seedCampaign(true, true);
        UUID contact = seedContact("Pat", null);
        seedEnrollment(campaign.getId(), contact);

        runner.runDueOnce().block();

        assertThat(smsBodies).singleElement().isEqualTo("Hey Pat! Still keen to get that sorted?");
        // The AI rewrite hit WireMock.
        wireMock.verify(1, postRequestedFor(urlPathEqualTo("/")));
        // The ledger recorded the AI flag.
        NurtureSendLog logRow = mongo.findAll(NurtureSendLog.class).collectList().block().get(0);
        assertThat(logRow.isAiPersonalizedApplied()).isTrue();
    }

    @Test
    void aiPersonalizeUpstreamError_degradesToTemplate_stillSends() {
        stubAnthropic500();
        connections.save(IntegrationConnection.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();

        NurtureCampaign campaign = seedCampaign(true, true);
        UUID contact = seedContact("Pat", null);
        seedEnrollment(campaign.getId(), contact);

        runner.runDueOnce().block();

        // Best-effort: the 500 was swallowed and the templated copy was sent (send NOT dropped).
        assertThat(smsBodies).singleElement().isEqualTo("Hi Pat, still need a hand?");
        assertThat(mongo.findAll(NurtureSendLog.class).collectList().block()).hasSize(1);
    }
}
