package com.kumouri.kmodigipresbe.integration.postmark;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.EmailDeliverabilityStatus;
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
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.Disposable;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * H.7 — PostmarkBounceIT: AC-H5 integration-test proof for the Postmark bounce/spam path.
 *
 * <p>Mirrors the existing {@code PostmarkWebhookIT} shape (in this same package),
 * focused on the new H.4 bounce/spam deliverability path.
 *
 * <h2>AC-H5 — Bounce → Contact.emailDeliverability=BOUNCED + EMAIL_BOUNCED event</h2>
 * POST valid Bounce webhook → within Awaitility bounded wait:
 * {@code Contact.emailDeliverability == BOUNCED} +
 * {@code EmailEngagement} with BOUNCE event saved +
 * {@code EMAIL_BOUNCED} observed via {@code eventPublisher.stream()}.
 *
 * <h2>AC-H5 — SpamComplaint → SPAM_COMPLAINED + EMAIL_SPAM</h2>
 * POST valid SpamComplaint → {@code emailDeliverability == SPAM_COMPLAINED} +
 * {@code EMAIL_SPAM} observed.
 *
 * <h2>AC-H5 — duplicate (same MessageID+RecordType) → idempotent 200 no-op</h2>
 * Re-posting the same Bounce with the same MessageID → 200, zero second engagement row.
 *
 * <h2>AC-H5 — unsigned/bad-auth → 401</h2>
 * Wrong Basic-Auth password → 401 + zero engagement row.
 *
 * <h2>AC-H5 — Delivery/Open/Click still recorded (no regression)</h2>
 * Open webhook → EmailEngagement saved as before (existing path unchanged).
 *
 * <p>No {@code @MockBean} — shard-safe. Self-clean {@code mongo.remove} in {@code @BeforeEach}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class PostmarkBounceIT {

    private static final String WEBHOOK_PASSWORD = "pm_h7_secret";
    private static final String BOUNCE_EMAIL = "bounced@postmark-it.test";

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired DomainEventPublisher eventPublisher;

    private UUID tenantId;
    private UUID contactId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), EmailEngagement.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("postmark-bounce-" + tenantId)
                .displayName("Postmark Bounce IT Tenant").status(Tenant.TenantStatus.ACTIVE).build())
                .block();

        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("postmark")
                .secrets(new HashMap<>(Map.of("webhookBasicAuthPassword", WEBHOOK_PASSWORD)))
                .build()).block();

        // Seed a Contact with the bounce email for deliverability-flag assertion.
        contactId = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(contactId)
                .tenantId(tenantId)
                .firstName("Bounce")
                .lastName("Target")
                .emails(List.of(new EmailContact(BOUNCE_EMAIL)))
                .build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    private String basicAuth(String password) {
        return "Basic " + Base64.getEncoder().encodeToString(
                ("postmark:" + password).getBytes(StandardCharsets.UTF_8));
    }

    private String bounceBody(String messageId) {
        return "{"
                + "\"RecordType\":\"Bounce\","
                + "\"MessageID\":\"" + messageId + "\","
                + "\"Recipient\":\"" + BOUNCE_EMAIL + "\","
                + "\"BouncedAt\":\"2026-05-18T12:00:00.000Z\","
                + "\"Email\":\"" + BOUNCE_EMAIL + "\""
                + "}";
    }

    private String spamBody(String messageId) {
        return "{"
                + "\"RecordType\":\"SpamComplaint\","
                + "\"MessageID\":\"" + messageId + "\","
                + "\"Recipient\":\"" + BOUNCE_EMAIL + "\","
                + "\"ReceivedAt\":\"2026-05-18T12:00:00.000Z\""
                + "}";
    }

    private String openBody(String messageId) {
        return "{"
                + "\"RecordType\":\"Open\","
                + "\"MessageID\":\"" + messageId + "\","
                + "\"Recipient\":\"" + BOUNCE_EMAIL + "\","
                + "\"ReceivedAt\":\"2026-05-18T12:00:00.000Z\","
                + "\"Metadata\":{\"kmosf_contact_id\":\"" + contactId + "\"}"
                + "}";
    }

    // -------------------------------------------------------------------------
    // AC-H5: Bounce → Contact.emailDeliverability=BOUNCED + EMAIL_BOUNCED
    // -------------------------------------------------------------------------

    @Test
    void bounce_contactFlaggedBounced_emailBouncedEventObserved() {
        String messageId = "pmb-h7-bounce-" + UUID.randomUUID();

        web.post()
                .uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", basicAuth(WEBHOOK_PASSWORD))
                .bodyValue(bounceBody(messageId))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // Awaitility: EMAIL_BOUNCED observed.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed).anyMatch(e ->
                        DomainEventType.EMAIL_BOUNCED.equals(e.type())));

        // EmailEngagement row with BOUNCE event.
        List<EmailEngagement> engagements = mongo.findAll(EmailEngagement.class).collectList().block();
        assertThat(engagements)
                .as("AC-H5: exactly one EmailEngagement (BOUNCE) must be created")
                .hasSize(1);
        assertThat(engagements.get(0).getMessageId()).isEqualTo(messageId);

        // Contact.emailDeliverability == BOUNCED (best-effort flag; Awaitility for async flag update).
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Contact contact = mongo.findOne(
                    Query.query(Criteria.where("_id").is(contactId)), Contact.class).block();
            assertThat(contact).isNotNull();
            assertThat(contact.getEmailDeliverability())
                    .as("AC-H5: Contact.emailDeliverability must be BOUNCED after bounce webhook")
                    .isEqualTo(EmailDeliverabilityStatus.BOUNCED);
        });
    }

    // -------------------------------------------------------------------------
    // AC-H5: SpamComplaint → SPAM_COMPLAINED + EMAIL_SPAM
    // -------------------------------------------------------------------------

    @Test
    void spamComplaint_contactFlaggedSpam_emailSpamEventObserved() {
        String messageId = "pmb-h7-spam-" + UUID.randomUUID();

        web.post()
                .uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", basicAuth(WEBHOOK_PASSWORD))
                .bodyValue(spamBody(messageId))
                .exchange()
                .expectStatus().is2xxSuccessful();

        // EMAIL_SPAM observed.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(observed).anyMatch(e ->
                        DomainEventType.EMAIL_SPAM.equals(e.type())));

        // Contact.emailDeliverability == SPAM_COMPLAINED.
        Awaitility.await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            Contact contact = mongo.findOne(
                    Query.query(Criteria.where("_id").is(contactId)), Contact.class).block();
            assertThat(contact).isNotNull();
            assertThat(contact.getEmailDeliverability())
                    .as("AC-H5: Contact.emailDeliverability must be SPAM_COMPLAINED after spam webhook")
                    .isEqualTo(EmailDeliverabilityStatus.SPAM_COMPLAINED);
        });
    }

    // -------------------------------------------------------------------------
    // AC-H5: duplicate (same MessageID+RecordType) → idempotent 200 no-op
    // -------------------------------------------------------------------------

    @Test
    void duplicateBounce_idempotent200NoOp_zeroSecondEngagementRow() {
        String messageId = "pmb-h7-dup-" + UUID.randomUUID();
        String body = bounceBody(messageId);

        // First delivery.
        web.post()
                .uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", basicAuth(WEBHOOK_PASSWORD))
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        Awaitility.await().atMost(8, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(mongo.findAll(EmailEngagement.class).collectList().block()).hasSize(1));

        // Re-deliver the same MessageID.
        web.post()
                .uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", basicAuth(WEBHOOK_PASSWORD))
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        try { Thread.sleep(400); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }

        // Still exactly one engagement row (no duplicate).
        assertThat(mongo.findAll(EmailEngagement.class).collectList().block())
                .as("AC-H5 duplicate: exactly one EmailEngagement row after re-delivery")
                .hasSize(1);
    }

    // -------------------------------------------------------------------------
    // AC-H5: unsigned/bad-auth → 401 + zero engagement row
    // -------------------------------------------------------------------------

    @Test
    void wrongBasicAuth_401_zeroEffect() {
        String body = bounceBody("pmb-h7-badauth-" + UUID.randomUUID());

        web.post()
                .uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", basicAuth("wrong_password"))
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1704);

        assertThat(mongo.findAll(EmailEngagement.class).collectList().block())
                .as("AC-H5 bad-auth: no EmailEngagement must be created")
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // AC-H5: Delivery/Open/Click still recorded as before (no regression)
    // -------------------------------------------------------------------------

    @Test
    void openWebhook_stillRecordedAsBeforeNoRegression() {
        String messageId = "pmb-h7-open-" + UUID.randomUUID();

        web.post()
                .uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Authorization", basicAuth(WEBHOOK_PASSWORD))
                .bodyValue(openBody(messageId))
                .exchange()
                .expectStatus().is2xxSuccessful();

        Awaitility.await().atMost(8, TimeUnit.SECONDS).untilAsserted(() -> {
            List<EmailEngagement> rows = mongo.findAll(EmailEngagement.class).collectList().block();
            assertThat(rows)
                    .as("AC-H5 regression: Open webhook must still produce an EmailEngagement row")
                    .hasSize(1);
        });

        // Contact deliverability must NOT be set by an Open event (only Bounce/Spam).
        Contact contact = mongo.findOne(
                Query.query(Criteria.where("_id").is(contactId)), Contact.class).block();
        assertThat(contact.getEmailDeliverability())
                .as("AC-H5 regression: Open event must not set Contact.emailDeliverability")
                .isNull();
    }
}
