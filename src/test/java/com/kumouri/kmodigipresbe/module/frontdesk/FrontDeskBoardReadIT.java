package com.kumouri.kmodigipresbe.module.frontdesk;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.twilio.voice.TwilioVoicemailService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.RecallLog;
import com.kumouri.kmodigipresbe.module.frontdesk.controller.dto.CallbackInboxItemDTO;
import com.kumouri.kmodigipresbe.module.frontdesk.controller.dto.RecallDueDTO;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FrontDesk IQ (FD-5a) — HTTP tests for the recall board + callback inbox reads
 * ({@code FrontDeskBoardController}). The {@code NoShowRiskControllerIT} / {@code WaitlistBoardController}
 * pattern: drives the endpoints over {@code WebTestClient} with a JWT STAFF token, seeding rows directly
 * via Mongo. A pure read surface — no WireMock, no Twilio.
 *
 * <h2>Coverage</h2>
 * <ol>
 *   <li>{@code GET /frontdesk/recall} returns ONLY the lapsed contact (last visit older than the recall
 *       window, no upcoming appointment) — not the contact with an upcoming appointment, and not the
 *       recently-seen contact — with the resolved name, days-since, and the nudged-this-period flag;</li>
 *   <li>{@code GET /frontdesk/callbacks} returns the FD-3 health voicemail callback with its logistics
 *       fields (name / callbackPhone / intentBucket) and <strong>NO transcript</strong> (fence F2 — the
 *       DTO has no body/transcript field and the serialized response carries no clinical token), and
 *       <strong>excludes</strong> a non-redacted (mole) inbound-call Activity that stores a transcript;</li>
 *   <li>a non-frontdesk tenant → 1132 module-gate not-enabled (both reads);</li>
 *   <li>a non-staff role → 1800 forbidden (both reads).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.frontdesk.enabled=true",
        // Pin the recall window so the seed math is deterministic regardless of the default.
        "kmosf.frontdesk.recall.window-days=180"
})
class FrontDeskBoardReadIT {

    private static final DateTimeFormatter ISO_WEEK = DateTimeFormatter.ofPattern("YYYY-'W'ww");

    /** A clinical token that must never appear in the callback-inbox response (fence F2). */
    private static final String CLINICAL_TOKEN = "Lisinopril";

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        wipe();
        tenantId = UUID.randomUUID();
        seedTenant(tenantId, Set.of("frontdesk"));

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@frontdesk.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @AfterEach
    void cleanup() {
        wipe();
    }

    private void wipe() {
        mongo.remove(new Query(), Appointment.class).block();
        mongo.remove(new Query(), RecallLog.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    // ── 1. recall board returns only the lapsed contact ───────────────────────────

    @Test
    void recallBoard_returnsOnlyLapsedContacts() {
        Instant now = Instant.now();

        // Lapsed: last visit 200d ago (> 180d window) and NO upcoming appointment.
        UUID lapsed = seedContact("Dana Reyes");
        seedAppointment(lapsed, now.minus(Duration.ofDays(200)), AppointmentStatus.COMPLETED);
        // FD-2 sweep already nudged this lapsed contact this period.
        seedRecallLog(lapsed, true);

        // NOT lapsed (has an upcoming appointment) even though the last visit is old.
        UUID upcoming = seedContact("Pat Upcoming");
        seedAppointment(upcoming, now.minus(Duration.ofDays(220)), AppointmentStatus.COMPLETED);
        seedAppointment(upcoming, now.plus(Duration.ofDays(3)), AppointmentStatus.SCHEDULED);

        // NOT lapsed (recently seen, inside the window).
        UUID recent = seedContact("Sam Recent");
        seedAppointment(recent, now.minus(Duration.ofDays(20)), AppointmentStatus.COMPLETED);

        List<RecallDueDTO> rows = web.get().uri("/frontdesk/recall")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(RecallDueDTO.class).returnResult().getResponseBody();

        assertThat(rows).isNotNull();
        assertThat(rows).extracting(RecallDueDTO::contactId).containsExactly(lapsed);
        RecallDueDTO row = rows.get(0);
        assertThat(row.name()).isEqualTo("Dana Reyes");
        assertThat(row.daysSinceLastVisit()).isGreaterThanOrEqualTo(199);
        assertThat(row.nudgedThisPeriod()).isTrue();
        assertThat(row.lastVisitAt()).isNotNull();
    }

    // ── 2. callback inbox returns the F2-redacted health callback, transcript-free ──

    @Test
    void callbackInbox_returnsLogisticsOnly_neverTranscript() {
        Instant now = Instant.now();
        UUID caller = seedContact("Dana Reyes");

        // The FD-3 health callback: CALL/INBOUND, body = the F2 redaction marker, logistics-only payload.
        Map<String, Object> extracted = new HashMap<>();
        extracted.put("name", "Dana Reyes");
        extracted.put("callbackNumber", "555-0142");
        extracted.put("intentBucket", "PRESCRIPTION_REFILL_REQUEST");
        extracted.put("callbackRequested", true);
        Map<String, Object> payload = new HashMap<>();
        payload.put("callSid", "CA_fd5_" + UUID.randomUUID());
        payload.put("fromNumber", "+16185550142");
        payload.put("extractedJson", extracted);
        payload.put("transcriptionSource", "TWILIO_BUILTIN");
        seedActivity(caller, TwilioVoicemailService.TRANSCRIPT_REDACTED_MARKER, payload, now);

        // A NON-redacted (mole) inbound-call Activity that DOES store a transcript — must be EXCLUDED
        // (it carries the clinical token in its body; if the read leaked it, the F2 scan below would trip).
        Map<String, Object> molePayload = new HashMap<>();
        molePayload.put("fromNumber", "+16185559999");
        seedActivity(seedContact("Mole Caller"),
                "I think I have a mole and my " + CLINICAL_TOKEN + " refill is due", molePayload,
                now.minus(Duration.ofMinutes(5)));

        List<CallbackInboxItemDTO> rows = web.get().uri("/frontdesk/callbacks")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBodyList(CallbackInboxItemDTO.class).returnResult().getResponseBody();

        assertThat(rows).isNotNull();
        // Only the health callback — the mole transcript Activity is filtered out by the F2-marker query.
        assertThat(rows).hasSize(1);
        CallbackInboxItemDTO row = rows.get(0);
        assertThat(row.contactId()).isEqualTo(caller);
        assertThat(row.callerName()).isEqualTo("Dana Reyes");
        assertThat(row.callbackPhone()).isEqualTo("555-0142");
        assertThat(row.intentBucket()).isEqualTo("PRESCRIPTION_REFILL_REQUEST");
        assertThat(row.callbackRequested()).isTrue();
        assertThat(row.receivedAt()).isNotNull();

        // F2 — the response shape has no transcript field, and no clinical token appears ANYWHERE.
        String json = web.get().uri("/frontdesk/callbacks")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(json).isNotNull();
        assertThat(json.toLowerCase())
                .as("the callback inbox response must never contain a transcript / clinical token (F2)")
                .doesNotContain(CLINICAL_TOKEN.toLowerCase());
        assertThat(json)
                .as("no transcript/body/recording field is exposed (F2)")
                .doesNotContain("transcript")
                .doesNotContain("recordingUrl")
                .doesNotContain("recordingSid");
    }

    // ── 3. non-frontdesk tenant → 1132 module gate (both reads) ────────────────────

    @Test
    void nonFrontdeskTenant_isModuleGated_1132() {
        Tenant t = tenants.findById(tenantId).block();
        t.setEnabledModules(Set.of()); // drop frontdesk
        tenants.save(t).block();

        web.get().uri("/frontdesk/recall")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);

        web.get().uri("/frontdesk/callbacks")
                .header("Authorization", staffToken)
                .exchange().expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1132);
    }

    // ── 4. non-staff role → 1800 forbidden (both reads) ────────────────────────────

    @Test
    void nonStaff_isForbidden_1800() {
        User noRole = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("norole@frontdesk.test")
                .roles(Set.of()).status(User.UserStatus.ACTIVE).build();
        users.save(noRole).block();
        String noRoleToken = "Bearer " + jwt.mint(noRole);

        web.get().uri("/frontdesk/recall")
                .header("Authorization", noRoleToken)
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);

        web.get().uri("/frontdesk/callbacks")
                .header("Authorization", noRoleToken)
                .exchange().expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private void seedTenant(UUID tid, Set<String> modules) {
        tenants.save(Tenant.builder()
                .id(tid).slug("fd5-board-it-" + tid)
                .displayName("FrontDesk IQ FD-5a IT Practice").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(modules)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    private UUID seedContact(String displayName) {
        UUID id = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(id).tenantId(tenantId)
                .displayName(displayName)
                .build()).block();
        return id;
    }

    private void seedAppointment(UUID contactId, Instant start, AppointmentStatus status) {
        mongo.save(Appointment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactId(contactId)
                .scheduledStart(start)
                .scheduledEnd(start.plus(Duration.ofMinutes(30)))
                .status(status)
                .visitTypeBucket(VisitTypeBucket.RECALL)
                .build()).block();
    }

    private void seedRecallLog(UUID contactId, boolean nudged) {
        String periodKey = ISO_WEEK.format(Instant.now().atZone(ZoneOffset.UTC));
        mongo.save(RecallLog.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .contactId(contactId).periodKey(periodKey)
                .enrolled(false).nudged(nudged)
                .sentAt(Instant.now())
                .build()).block();
    }

    private void seedActivity(UUID contactId, String body, Map<String, Object> payload, Instant occurredAt) {
        mongo.save(Activity.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .type(ActivityType.CALL)
                .direction(ActivityDirection.INBOUND)
                .subjectType(SubjectType.CONTACT)
                .subjectId(contactId)
                .summary("Callback")
                .body(body)
                .payload(payload)
                .occurredAt(occurredAt)
                .build()).block();
    }
}
