package com.kumouri.kmodigipresbe.module.frontdesk;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.sequence.Sequence;
import com.kumouri.kmodigipresbe.model.sequence.SequenceEnrollment;
import com.kumouri.kmodigipresbe.model.sequence.SequenceStep;
import com.kumouri.kmodigipresbe.model.sequence.SequenceStepType;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.ConfirmationLog;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.FrontDeskConfirmationService;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.RecallDetectorJob;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.RecallLog;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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

/**
 * FrontDesk IQ (FD-2) integration tests — the risk-tiered confirmation subscriber
 * ({@link FrontDeskConfirmationService}) + the recall/recare sweep ({@link RecallDetectorJob}). Drives the
 * subscriber end-to-end via its visible-for-test {@code handle(event)} and the sweep via
 * {@code sweepDueOnce().block()} (the {@code RiskTieredPreventionIT} / {@code CoverageNudgeIT} posture —
 * deterministic, no live event-bus race / no cron wait). Anthropic goes to WireMock via
 * {@code kmosf.ai.anthropic.base-url}; {@link TwilioSmsService} is the {@code @MockitoBean} capture seam.
 *
 * <h2>Coverage (plan FD-2 ITs + hard gates)</h2>
 * <ol>
 *   <li>HIGH-risk scored appointment ⇒ an extra confirmation SMS (asks to confirm), no deposit;</li>
 *   <li>LOW ⇒ exactly ONE light reminder SMS, no deposit;</li>
 *   <li><strong>the F3 generic-copy fence</strong>: no procedure/provider/visit-type/clinical token in ANY
 *       outbound body — asserted on the Claude path, the generic fallback, and the recall nudge;</li>
 *   <li>idempotent on a re-fired event (zero duplicate SMS, one ledger row);</li>
 *   <li>a non-frontdesk tenant is a hard no-op (no SMS, no ledger);</li>
 *   <li>TCPA: an opted-out contact gets NO SMS;</li>
 *   <li>best-effort: a Claude 500 still sends a GENERIC reminder (no error, personalized=false);</li>
 *   <li>recall: a lapsed contact (&gt;180d, no upcoming) enrolls into the recall Sequence + gets a generic
 *       nudge; a contact WITH an upcoming appointment is not recalled; the recall sweep is idempotent per
 *       period.</li>
 * </ol>
 *
 * <h2>§7 no-live-external</h2>
 * Anthropic → WireMock (never a real host); Twilio → a mock bean. No outbound network.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.frontdesk.confirmation-draft-model=claude-haiku-4-5",
        // Generous frequency cap so a single-appointment flow isn't capped.
        "kmosf.frontdesk.confirmation.max-per-contact-per-window=5",
        "kmosf.frontdesk.confirmation.window-hours=24",
        // Recall config: 180d window, the named recall Sequence the IT seeds.
        "kmosf.frontdesk.recall.window-days=180",
        "kmosf.frontdesk.recall.sequence-name=frontdesk-recall"
})
class FrontDeskConfirmationIT {

    private static final String ANTHROPIC_API_KEY = "sk-ant-test-frontdesk-fake";
    private static final String PATIENT_PHONE = "+16185550143";

    /**
     * The F3 forbidden-token set — clinical / provider / visit-type words that must NEVER appear in any
     * outbound body. The marquee PHI fence: a leak here is a reputational/legal event, not a bug.
     * Lower-cased + matched case-insensitively against the SMS body.
     */
    private static final String[] FORBIDDEN_TOKENS = {
            "root canal", "crown", "filling", "cleaning", "extraction", "implant",
            "procedure", "treatment", "diagnos", "surgery", "oncology", "cancer",
            "blood pressure", "prescription", "medication", "refill", "x-ray", "xray",
            "biopsy", "follow-up", "follow up", "hygiene", "wellness", "vaccine", "vaccination",
            "dr.", "doctor", "dentist", "physician", "provider", "specialist",
            "new patient", "new-patient", "recall", "annual"
    };

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

    @Autowired FrontDeskConfirmationService confirmationService;
    @Autowired RecallDetectorJob recallDetectorJob;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();

    private UUID tenantId;

    @BeforeEach
    void seed() {
        wireMock.resetAll();
        mongo.remove(new Query(), Appointment.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), ConfirmationLog.class).block();
        mongo.remove(new Query(), RecallLog.class).block();
        mongo.remove(new Query(), Sequence.class).block();
        mongo.remove(new Query(), SequenceEnrollment.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));
        seedAnthropic(tenantId);
    }

    private DomainEvent riskEvent(UUID tid, UUID appointmentId, UUID contactId, String tier) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("appointmentId", appointmentId);
        payload.put("contactId", contactId);
        payload.put("providerId", UUID.randomUUID());
        payload.put("riskTier", tier);
        payload.put("riskScore", NoShowRisk.TIER_HIGH.equals(tier) ? 0.82 : 0.2);
        payload.put("source", NoShowRisk.SOURCE_MODEL);
        return DomainEvent.of(DomainEventType.APPOINTMENT_RISK_SCORED, tid, appointmentId, payload);
    }

    // ── 1. HIGH ⇒ an extra confirmation SMS, no deposit ──────────────────────────

    @Test
    void highRisk_sendsConfirmationAsk_genericCopy_noDeposit() {
        // A compliant (already-generic) Claude reply. Even so, the F3 assertion below proves it is generic.
        stubReply("Hi Dana! Just confirming your visit Thursday at 2:00. Reply YES to confirm. Reply STOP to opt out.");

        UUID contactId = seedContact("Dana", PATIENT_PHONE, Set.of());
        UUID apptId = seedUpcoming(tenantId, contactId, Instant.now().plus(Duration.ofDays(2)));

        confirmationService.handle(riskEvent(tenantId, apptId, contactId, NoShowRisk.TIER_HIGH)).block();

        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(PATIENT_PHONE);
        assertGeneric(sentSms.get(0).body());

        // Ledger: a single row, confirmation=true, personalized=true, no deposit concept exists.
        ConfirmationLog row = mongo.findAll(ConfirmationLog.class).collectList().block().get(0);
        assertThat(row.isConfirmation()).isTrue();
        assertThat(row.isPersonalized()).isTrue();
        assertThat(row.getRiskTier()).isEqualTo(NoShowRisk.TIER_HIGH);
    }

    // ── 2. LOW ⇒ a single light reminder SMS ─────────────────────────────────────

    @Test
    void lowRisk_sendsSingleReminder_genericCopy() {
        stubReply("Hi Sam! Looking forward to seeing you for your visit Friday at 10:00. Reply STOP to opt out.");

        UUID contactId = seedContact("Sam", PATIENT_PHONE, Set.of());
        UUID apptId = seedUpcoming(tenantId, contactId, Instant.now().plus(Duration.ofDays(3)));

        confirmationService.handle(riskEvent(tenantId, apptId, contactId, NoShowRisk.TIER_LOW)).block();

        assertThat(sentSms).hasSize(1);
        assertGeneric(sentSms.get(0).body());

        ConfirmationLog row = mongo.findAll(ConfirmationLog.class).collectList().block().get(0);
        assertThat(row.isConfirmation()).isFalse();
        assertThat(row.getRiskTier()).isEqualTo(NoShowRisk.TIER_LOW);
    }

    // ── 3. F3 fence — the generic-copy guard on the DETERMINISTIC fallback ───────

    /**
     * The marquee PHI test on the fallback path: even when Claude is unavailable, the deterministic template
     * is GENERIC — it can name no procedure/provider/visit-type because no such value is ever passed to it.
     * Asserted against the full forbidden-token set.
     */
    @Test
    void f3_fallbackCopyIsGeneric_noClinicalOrProviderToken() {
        // Upstream 500 -> ConfirmationCopyService surfaces 1202 -> the subscriber degrades to generic.
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse().withStatus(500).withBody("upstream boom")));

        UUID contactId = seedContact("Alex", PATIENT_PHONE, Set.of());
        UUID apptId = seedUpcoming(tenantId, contactId, Instant.now().plus(Duration.ofDays(2)));

        // HIGH so the confirm branch of the template is exercised too.
        confirmationService.handle(riskEvent(tenantId, apptId, contactId, NoShowRisk.TIER_HIGH)).block();

        assertThat(sentSms).hasSize(1);
        String body = sentSms.get(0).body();
        assertGeneric(body);
        // The fallback still greets + asks to confirm, just generically.
        assertThat(body).containsIgnoringCase("Alex");
        assertThat(body.toLowerCase(Locale.ROOT)).contains("confirm");

        ConfirmationLog row = mongo.findAll(ConfirmationLog.class).collectList().block().get(0);
        assertThat(row.isPersonalized()).isFalse(); // fallback was used
    }

    // ── 4. idempotent on a re-fired event ────────────────────────────────────────

    @Test
    void reFiredEvent_isIdempotent_zeroDuplicate() {
        stubReply("Hi Pat! Confirming your visit Monday at 9:00. Reply YES. Reply STOP to opt out.");

        UUID contactId = seedContact("Pat", PATIENT_PHONE, Set.of());
        UUID apptId = seedUpcoming(tenantId, contactId, Instant.now().plus(Duration.ofDays(2)));

        DomainEvent ev = riskEvent(tenantId, apptId, contactId, NoShowRisk.TIER_HIGH);
        confirmationService.handle(ev).block();
        assertThat(sentSms).hasSize(1);

        confirmationService.handle(ev).block(); // re-fire
        assertThat(sentSms).hasSize(1); // still exactly one — ledger-insert-FIRST dedupe

        Long ledgerForAppt = mongo.count(
                new Query(Criteria.where("appointmentId").is(apptId)), ConfirmationLog.class).block();
        assertThat(ledgerForAppt).isEqualTo(1L);
    }

    // ── 5. non-frontdesk tenant ⇒ hard no-op ─────────────────────────────────────

    @Test
    void nonFrontdeskTenant_isHardNoOp_evenWhenEventFired() {
        UUID otherTenant = UUID.randomUUID();
        seedTenant(otherTenant, Set.of("salon-spa")); // no frontdesk
        seedAnthropic(otherTenant);
        UUID contactId = seedContactFor(otherTenant, "Lee", PATIENT_PHONE, Set.of());
        UUID apptId = seedUpcomingFor(otherTenant, contactId, Instant.now().plus(Duration.ofDays(2)));
        stubReply("(should never be called)");

        confirmationService.handle(riskEvent(otherTenant, apptId, contactId, NoShowRisk.TIER_HIGH)).block();

        assertThat(sentSms).isEmpty();
        assertThat(mongo.count(new Query(), ConfirmationLog.class).block()).isEqualTo(0L);
        wireMock.verify(0, postRequestedFor(urlPathEqualTo("/")));
    }

    // ── 6. TCPA consent gate ──────────────────────────────────────────────────────

    @Test
    void optedOutContact_getsNoSms() {
        stubReply("Hi! Reminder about your visit.");

        UUID optedOut = seedContact("Optout", PATIENT_PHONE,
                Set.of(FrontDeskConfirmationService.SMS_OPT_OUT_TAG));
        UUID apptId = seedUpcoming(tenantId, optedOut, Instant.now().plus(Duration.ofDays(2)));

        confirmationService.handle(riskEvent(tenantId, apptId, optedOut, NoShowRisk.TIER_LOW)).block();

        assertThat(sentSms).isEmpty();
        assertThat(mongo.count(new Query(), ConfirmationLog.class).block()).isEqualTo(0L);
    }

    // ── 7. recall sweep: lapsed ⇒ enroll + nudge; upcoming ⇒ not recalled ─────────

    @Test
    void recallSweep_lapsedContactEnrolledAndNudged_genericCopy() {
        UUID recallSeqId = seedRecallSequence(tenantId);

        // Lapsed: a COMPLETED appointment 300 days ago, no upcoming.
        UUID lapsed = seedContact("Jordan", PATIENT_PHONE, Set.of());
        seedAppointment(tenantId, lapsed, AppointmentStatus.COMPLETED,
                Instant.now().minus(Duration.ofDays(300)));

        // Active: a COMPLETED appointment 300 days ago BUT an upcoming SCHEDULED one — not lapsed.
        UUID active = seedContact("Casey", "+16185550199", Set.of());
        seedAppointment(tenantId, active, AppointmentStatus.COMPLETED,
                Instant.now().minus(Duration.ofDays(300)));
        seedUpcoming(tenantId, active, Instant.now().plus(Duration.ofDays(5)));

        recallDetectorJob.sweepDueOnce().block();

        // The lapsed contact got a generic recare nudge.
        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(PATIENT_PHONE);
        assertGeneric(sentSms.get(0).body());

        // ...and was enrolled into the recall Sequence.
        List<SequenceEnrollment> enrollments = mongo.findAll(SequenceEnrollment.class).collectList().block();
        assertThat(enrollments).hasSize(1);
        assertThat(enrollments.get(0).getSequenceId()).isEqualTo(recallSeqId);
        assertThat(enrollments.get(0).getContactId()).isEqualTo(lapsed);

        // The recall ledger has exactly one row (the lapsed contact), enrolled + nudged.
        List<RecallLog> logs = mongo.findAll(RecallLog.class).collectList().block();
        assertThat(logs).hasSize(1);
        assertThat(logs.get(0).getContactId()).isEqualTo(lapsed);
        assertThat(logs.get(0).isEnrolled()).isTrue();
        assertThat(logs.get(0).isNudged()).isTrue();
    }

    // ── 8. recall sweep is idempotent per period ─────────────────────────────────

    @Test
    void recallSweep_isIdempotent_perPeriod() {
        seedRecallSequence(tenantId);

        UUID lapsed = seedContact("Riley", PATIENT_PHONE, Set.of());
        seedAppointment(tenantId, lapsed, AppointmentStatus.COMPLETED,
                Instant.now().minus(Duration.ofDays(300)));

        recallDetectorJob.sweepDueOnce().block();
        assertThat(sentSms).hasSize(1);

        recallDetectorJob.sweepDueOnce().block(); // re-run same period
        assertThat(sentSms).hasSize(1); // still exactly one — ledger-insert-FIRST dedupe

        // One enrollment, one recall-log row.
        assertThat(mongo.count(new Query(), SequenceEnrollment.class).block()).isEqualTo(1L);
        assertThat(mongo.count(new Query(), RecallLog.class).block()).isEqualTo(1L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    /**
     * The load-bearing PHI assertion: the body contains NONE of the forbidden clinical/provider/visit-type
     * tokens (case-insensitive). The fence the whole product rides on.
     */
    private void assertGeneric(String body) {
        assertThat(body).isNotBlank();
        String lower = body.toLowerCase(Locale.ROOT);
        for (String bad : FORBIDDEN_TOKENS) {
            assertThat(lower.contains(bad))
                    .as("Outbound copy must be PHI-free (fence F3) — body \"" + body
                            + "\" contains forbidden token '" + bad + "'")
                    .isFalse();
        }
    }

    private void stubReply(String replyText) {
        wireMock.stubFor(post(urlPathEqualTo("/"))
                .willReturn(aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\","
                                + "\"content\":[{\"type\":\"text\",\"text\":\"" + replyText + "\"}],"
                                + "\"usage\":{\"input_tokens\":120,\"output_tokens\":30}}")));
    }

    private void seedTenant(UUID tid, Set<String> modules) {
        mongo.save(Tenant.builder()
                .id(tid).slug("frontdesk-fd2-" + tid)
                .displayName("FrontDesk IQ FD-2 IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(new BigDecimal("5.00")) // non-zero so the budget gate passes
                .build()).block();
    }

    private void seedAnthropic(UUID tid) {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tid)
                .provider("anthropic")
                .secrets(new HashMap<>(Map.of("apiKey", ANTHROPIC_API_KEY)))
                .build()).block();
    }

    private UUID seedRecallSequence(UUID tid) {
        Sequence seq = Sequence.builder()
                .id(UUID.randomUUID())
                .tenantId(tid)
                .name("frontdesk-recall")
                .description("FD-2 recall cadence")
                .status(Sequence.Status.ACTIVE)
                .steps(List.of(SequenceStep.builder()
                        .stepIndex(0)
                        .type(SequenceStepType.EXIT)
                        .build()))
                .build();
        return mongo.save(seq).block().getId();
    }

    private UUID seedContact(String firstName, String phone, Set<String> tags) {
        return seedContactFor(tenantId, firstName, phone, tags);
    }

    private UUID seedContactFor(UUID tid, String firstName, String phone, Set<String> tags) {
        Contact c = Contact.builder()
                .id(UUID.randomUUID()).tenantId(tid)
                .type(ContactType.PERSON)
                .firstName(firstName).displayName(firstName)
                .phones(List.of(PhoneNumber.builder().number(phone).label("mobile").build()))
                .tags(tags)
                .build();
        return mongo.save(c).block().getId();
    }

    private UUID seedUpcoming(UUID tid, UUID contactId, Instant start) {
        return seedAppointment(tid, contactId, AppointmentStatus.SCHEDULED, start);
    }

    private UUID seedUpcomingFor(UUID tid, UUID contactId, Instant start) {
        return seedAppointment(tid, contactId, AppointmentStatus.SCHEDULED, start);
    }

    private UUID seedAppointment(UUID tid, UUID contactId, AppointmentStatus status, Instant start) {
        Appointment a = Appointment.builder()
                .id(UUID.randomUUID())
                .tenantId(tid)
                .contactId(contactId)
                .providerId(UUID.randomUUID())
                .scheduledStart(start)
                .scheduledEnd(start.plus(Duration.ofMinutes(30)))
                .status(status)
                .visitTypeBucket(VisitTypeBucket.RECALL)
                .build();
        return mongo.save(a).block().getId();
    }
}
