package com.kumouri.kmodigipresbe.integration.calcom;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.integration.CalComWebhookEvent;
import com.kumouri.kmodigipresbe.model.meeting.Meeting;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H.7 — CalComWebhookIT: AC-H1/H5 integration-test proof.
 *
 * <p>Mirrors {@code DocumensoWebhookSignedIT} exactly — inbound signed webhook,
 * NO WireMock needed (all assertions are DB-side + DomainEvent stream).
 *
 * <h2>AC-H1 — booking-created happy path</h2>
 * POST valid signed BOOKING_CREATED → within Awaitility bounded wait:
 * {@code Meeting} upserted (keyed {@code calComBookingUid}) +
 * {@code Activity(type=MEETING, subjectType=CONTACT)} created +
 * {@code CALCOM_BOOKING_SYNCED} observed via {@code eventPublisher.stream()}.
 *
 * <h2>AC-H1 — booking-cancelled</h2>
 * BOOKING_CANCELLED on the same bookingUid → projection name prefixed with
 * {@code [CANCELLED]} + {@code CALCOM_BOOKING_CANCELLED} observed.
 *
 * <h2>AC-H5 — duplicate redelivery (same Cal.com event id) → 200 no-op</h2>
 * Re-POST the identical event id → 200, zero second Meeting/Activity row.
 *
 * <h2>AC-H5 — unsigned / bad-sig → 401 (errorCode 3900), zero effect</h2>
 * Invalid {@code X-Cal-Signature-256} → 401 + no ledger row + no Meeting.
 *
 * <h2>AC-H5 — cross-tenant (not-connected) → 3901, zero effect</h2>
 * Tenant without an IntegrationConnection → 3901.
 *
 * <p>No {@code @MockBean} — shard-safe. Self-clean {@code mongo.remove} in
 * {@code @BeforeEach} for every collection touched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class CalComWebhookIT {

    private static final String TEST_SIGNING_SECRET = "calcom_test_secret_phaseH_webhook";
    private static final String BOOKING_UID = "booking-uid-h7-test";
    private static final String ATTENDEE_EMAIL = "attendee@calcom-it.test";

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        // Self-clean every collection this IT touches.
        mongo.remove(new Query(), CalComWebhookEvent.class).block();
        mongo.remove(new Query(), Meeting.class).block();
        mongo.remove(new Query(), Activity.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("calcom-wh-" + tenantId)
                .displayName("CalCom IT Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("calcom")
                .secrets(new HashMap<>(Map.of(
                        "webhookSigningSecret", TEST_SIGNING_SECRET)))
                .build()).block();

        // Seed a Contact with the attendee email — so Activity creation has a target.
        mongo.save(Contact.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .firstName("Attendee")
                .lastName("User")
                .emails(List.of(new EmailContact(ATTENDEE_EMAIL)))
                .build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    // -------------------------------------------------------------------------
    // HMAC signing helper — mirrors CalComSignatureVerifier exactly.
    // -------------------------------------------------------------------------

    private String sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(
                    TEST_SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new RuntimeException("HMAC failed", ex);
        }
    }

    private String bookingCreatedBody(String eventId) {
        return "{"
                + "\"id\":\"" + eventId + "\","
                + "\"triggerEvent\":\"BOOKING_CREATED\","
                + "\"payload\":{"
                + "\"uid\":\"" + BOOKING_UID + "\","
                + "\"title\":\"H.7 IT Meeting\","
                + "\"startTime\":\"2026-06-01T10:00:00\","
                + "\"endTime\":\"2026-06-01T11:00:00\","
                + "\"attendees\":[{\"email\":\"" + ATTENDEE_EMAIL + "\",\"name\":\"Attendee User\"}],"
                + "\"organizer\":{\"email\":\"organizer@kmosf.test\",\"name\":\"Organizer\"}"
                + "}"
                + "}";
    }

    private String bookingCancelledBody(String eventId) {
        return "{"
                + "\"id\":\"" + eventId + "\","
                + "\"triggerEvent\":\"BOOKING_CANCELLED\","
                + "\"payload\":{"
                + "\"uid\":\"" + BOOKING_UID + "\","
                + "\"title\":\"H.7 IT Meeting\""
                + "}"
                + "}";
    }

    private void postWebhook(String body, String signature, int expectedStatus) {
        var req = web.post()
                .uri("/public/integrations/calcom/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Cal-Signature-256", signature != null ? signature : "")
                .bodyValue(body);
        req.exchange().expectStatus().isEqualTo(expectedStatus);
    }

    // -------------------------------------------------------------------------
    // AC-H1: BOOKING_CREATED → Meeting upserted + Activity + CALCOM_BOOKING_SYNCED
    // -------------------------------------------------------------------------

    @Test
    void bookingCreated_meetingUpsertedActivityCreatedEventObserved() {
        String eventId = "evt-h7-created-" + UUID.randomUUID();
        String body = bookingCreatedBody(eventId);

        postWebhook(body, sign(body), 200);

        // Awaitility: Meeting must be upserted with the correct calComBookingUid.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Meeting> meetings = mongo.findAll(Meeting.class).collectList().block();
            assertThat(meetings)
                    .as("AC-H1: exactly one Meeting must be created for the booking")
                    .hasSize(1);
            assertThat(meetings.get(0).getCalComBookingUid())
                    .as("AC-H1: Meeting.calComBookingUid must match the booking uid")
                    .isEqualTo(BOOKING_UID);
        });

        // Activity(MEETING, subjectType=CONTACT) created for the resolved contact.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Activity> activities = mongo.findAll(Activity.class).collectList().block();
            assertThat(activities)
                    .as("AC-H1: at least one Activity must be created for the booking")
                    .isNotEmpty();
            assertThat(activities).anyMatch(a ->
                    "MEETING".equals(a.getType() != null ? a.getType().name() : null));
        });

        // CALCOM_BOOKING_SYNCED observed.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed).anyMatch(e ->
                        DomainEventType.CALCOM_BOOKING_SYNCED.equals(e.type())));

        // One ledger row.
        List<CalComWebhookEvent> ledger = mongo.findAll(CalComWebhookEvent.class).collectList().block();
        assertThat(ledger).hasSize(1);
        assertThat(ledger.get(0).getCalComEventId()).isEqualTo(eventId);
    }

    // -------------------------------------------------------------------------
    // AC-H1: BOOKING_CANCELLED → projection cancelled + CALCOM_BOOKING_CANCELLED
    // -------------------------------------------------------------------------

    @Test
    void bookingCancelled_projectionCancelledEventObserved() {
        // First create the booking.
        String createEventId = "evt-h7-tocancell-" + UUID.randomUUID();
        String createBody = bookingCreatedBody(createEventId);
        postWebhook(createBody, sign(createBody), 200);

        // Wait for the Meeting to be created.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(Meeting.class).collectList().block()).hasSize(1));

        // Now cancel.
        String cancelEventId = "evt-h7-cancel-" + UUID.randomUUID();
        String cancelBody = bookingCancelledBody(cancelEventId);
        postWebhook(cancelBody, sign(cancelBody), 200);

        // Awaitility: Meeting must be marked cancelled.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Meeting> meetings = mongo.findAll(Meeting.class).collectList().block();
            assertThat(meetings).hasSize(1);
            assertThat(meetings.get(0).getName())
                    .as("AC-H1 cancel: Meeting name must be prefixed with [CANCELLED]")
                    .startsWith("[CANCELLED]");
        });

        // CALCOM_BOOKING_CANCELLED observed.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed).anyMatch(e ->
                        DomainEventType.CALCOM_BOOKING_CANCELLED.equals(e.type())));
    }

    // -------------------------------------------------------------------------
    // AC-H5: duplicate redelivery (same Cal.com event id) → 200 no-op, zero
    // second Meeting/Activity
    // -------------------------------------------------------------------------

    @Test
    void duplicateEventId_200NoOp_zeroSecondEffect() {
        String eventId = "evt-h7-dup-" + UUID.randomUUID();
        String body = bookingCreatedBody(eventId);
        String sig = sign(body);

        // First delivery.
        postWebhook(body, sig, 200);

        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(Meeting.class).collectList().block()).hasSize(1));

        // Re-deliver the SAME event id.
        postWebhook(body, sig, 200);

        // Brief processing window — no second effect.
        try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        // Still exactly one ledger row.
        assertThat(mongo.findAll(CalComWebhookEvent.class).collectList().block())
                .as("AC-H5 duplicate: exactly one ledger row after re-delivery")
                .hasSize(1);

        // Still exactly one Meeting.
        assertThat(mongo.findAll(Meeting.class).collectList().block())
                .as("AC-H5 duplicate: exactly one Meeting after re-delivery")
                .hasSize(1);
    }

    // -------------------------------------------------------------------------
    // AC-H5: unsigned / bad-sig → 401 errorCode 3900, zero effect
    // -------------------------------------------------------------------------

    @Test
    void invalidSignature_401_3900_zeroEffect() {
        String body = bookingCreatedBody("evt-h7-badsig-" + UUID.randomUUID());

        web.post()
                .uri("/public/integrations/calcom/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Cal-Signature-256", "deadbeef_invalid")
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(401)
                .expectBody().jsonPath("$.errorCode").isEqualTo(3900);

        // Zero effect.
        assertThat(mongo.findAll(CalComWebhookEvent.class).collectList().block())
                .as("AC-H5 bad-sig: no ledger row must be created")
                .isEmpty();
        assertThat(mongo.findAll(Meeting.class).collectList().block())
                .as("AC-H5 bad-sig: no Meeting must be created")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-H5: cross-tenant (not-connected) → 3901, zero effect
    // -------------------------------------------------------------------------

    @Test
    void notConnectedTenant_3901_zeroEffect() {
        UUID otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(otherTenantId).slug("calcom-notconn-" + otherTenantId)
                .displayName("Not-Connected Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();
        // No IntegrationConnection for otherTenantId.

        String body = bookingCreatedBody("evt-h7-notconn-" + UUID.randomUUID());

        web.post()
                .uri("/public/integrations/calcom/" + otherTenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Cal-Signature-256", sign(body))
                .bodyValue(body)
                .exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.errorCode").isEqualTo(3901);

        // Zero effect in ANY tenant.
        assertThat(mongo.findAll(CalComWebhookEvent.class).collectList().block())
                .as("AC-H5 cross-tenant: no ledger row for not-connected tenant")
                .isEmpty();
        assertThat(mongo.findAll(Meeting.class).collectList().block())
                .as("AC-H5 cross-tenant: no Meeting for not-connected tenant")
                .isEmpty();
    }
}
