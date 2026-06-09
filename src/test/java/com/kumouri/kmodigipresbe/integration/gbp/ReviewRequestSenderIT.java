package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * E3 Review Engine — ReviewRequestSenderIT: the default-OFF review-request sender. This IT opts the
 * sender ON ({@code kmosf.modules.review-engine.sender-enabled=true}) and drives {@code sendDueOnce()}
 * deterministically (the {@code CoverageNudgeIT} / {@code GbpReviewPollerIT} pattern). The Twilio SMS
 * seam is {@code @MockitoBean} (base-URL not config-driven) — the dispatch contract (recipient + body)
 * is still asserted (the {@code MoleTriageIT} / {@code OnTheWayDispatchIT} precedent). No live send.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>a due PENDING request with a connected reviewLink + a reachable contact → status SENT + SMS to
 *       the contact carrying the tenant's review link, and the template carries <strong>NO incentive
 *       language</strong>; a second sweep sends ZERO duplicate (atomic claim);</li>
 *   <li>a contact carrying {@code sms-opt-out} → SKIPPED, no SMS;</li>
 *   <li>a tenant with no {@code reviewLink} → the request stays PENDING (tenant skipped), no SMS;</li>
 *   <li>the per-contact frequency cap blocks a second request for the same contact in the window.</li>
 * </ul>
 *
 * <p>Shard-safe: the only mock is the precedented Twilio seam; self-clean {@code mongo.remove}; the
 * default-OFF sender is opted-ON only in this IT's {@code @TestPropertySource} (no global enable).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // Opt the default-OFF sender ON for this IT only.
        "kmosf.modules.review-engine.sender-enabled=true",
        "kmosf.review-engine.max-per-contact-per-window=1",
        "kmosf.review-engine.window-hours=720"
})
class ReviewRequestSenderIT {

    private static final String REVIEW_LINK = "https://g.page/r/test-nmm/review";
    private static final String PHONE = "+16185550144";

    @Autowired ReviewRequestSenderJob sender;
    @Autowired TenantRepository tenants;
    @Autowired ContactRepository contacts;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired ReviewRequestRepository reviewRequests;
    @Autowired DomainEventPublisher eventPublisher;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final List<String> smsBody = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsTo.clear();
        smsBody.clear();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            smsBody.add(req.body());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("review-sender-it-" + tenantId)
                .displayName("Review Sender IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private void seedTwilioWithReviewLink() {
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId)
                .provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "AC_test_re",
                        "authToken", "twilio_test_authtoken_re",
                        "fromNumber", "+16185550100")))
                .config(new HashMap<>(Map.of("reviewLink", REVIEW_LINK)))
                .build()).block();
    }

    private UUID seedContact(String phone, boolean optedOut) {
        UUID id = UUID.randomUUID();
        Contact.ContactBuilder b = Contact.builder()
                .id(id).tenantId(tenantId).firstName("Jane");
        if (phone != null) {
            b.phones(List.of(new PhoneNumber(phone, "mobile")));
        }
        if (optedOut) {
            b.tags(Set.of(ReviewRequestSenderJob.SMS_OPT_OUT_TAG));
        }
        contacts.save(b.build()).block();
        return id;
    }

    private UUID seedDueRequest(UUID contactId, UUID subjectId) {
        UUID id = UUID.randomUUID();
        reviewRequests.save(ReviewRequest.builder()
                .id(id).tenantId(tenantId)
                .subjectType(ReviewSubjectType.STAFF)
                .subjectId(subjectId)
                .contactId(contactId)
                .sourceEventType(DomainEventType.BOOKING_COMPLETED)
                .status(ReviewRequest.Status.PENDING)
                .dueAt(Instant.now().minus(1, ChronoUnit.HOURS)) // already due
                .build()).block();
        return id;
    }

    // -------------------------------------------------------------------------
    // Due PENDING -> SENT + SMS with the link, no-incentive template; 2nd sweep zero dup
    // -------------------------------------------------------------------------

    @Test
    void dueRequest_sentOnce_withLink_noIncentive_zeroDuplicateSecondSweep() {
        seedTwilioWithReviewLink();
        UUID contactId = seedContact(PHONE, false);
        UUID reqId = seedDueRequest(contactId, UUID.randomUUID());

        sender.sendDueOnce().block();

        ReviewRequest after = reviewRequests.findByTenantIdAndId(tenantId, reqId).block();
        assertThat(after).isNotNull();
        assertThat(after.getStatus()).isEqualTo(ReviewRequest.Status.SENT);
        assertThat(after.getSentAt()).isNotNull();

        assertThat(smsTo).containsExactly(PHONE);
        assertThat(smsBody).hasSize(1);
        String body = smsBody.get(0);
        // The tenant's review link was substituted into the message.
        assertThat(body).contains(REVIEW_LINK);
        assertThat(body).doesNotContain("{link}");
        // NO incentive language (Google 2026 policy).
        String lower = body.toLowerCase(Locale.ROOT);
        assertThat(lower).doesNotContain("discount");
        assertThat(lower).doesNotContain("coupon");
        assertThat(lower).doesNotContain("gift");
        assertThat(lower).doesNotContain("reward");
        assertThat(lower).doesNotContain("free ");
        assertThat(lower).doesNotContain("% off");

        assertThat(observed).filteredOn(e -> DomainEventType.REVIEW_REQUEST_SENT.equals(e.type()))
                .hasSize(1);

        // Second sweep: the request is already SENT → atomic claim finds nothing → zero duplicate SMS.
        sender.sendDueOnce().block();
        assertThat(smsTo).hasSize(1);
        assertThat(observed).filteredOn(e -> DomainEventType.REVIEW_REQUEST_SENT.equals(e.type()))
                .hasSize(1);
    }

    // -------------------------------------------------------------------------
    // Opted-out contact -> SKIPPED, no SMS
    // -------------------------------------------------------------------------

    @Test
    void optedOutContact_skipped_noSms() {
        seedTwilioWithReviewLink();
        UUID contactId = seedContact(PHONE, true); // sms-opt-out
        UUID reqId = seedDueRequest(contactId, UUID.randomUUID());

        sender.sendDueOnce().block();

        ReviewRequest after = reviewRequests.findByTenantIdAndId(tenantId, reqId).block();
        assertThat(after.getStatus()).isEqualTo(ReviewRequest.Status.SKIPPED);
        assertThat(smsTo).isEmpty();
        assertThat(observed).filteredOn(e -> DomainEventType.REVIEW_REQUEST_SKIPPED.equals(e.type()))
                .hasSize(1);
    }

    // -------------------------------------------------------------------------
    // No reviewLink -> request stays PENDING, no SMS
    // -------------------------------------------------------------------------

    @Test
    void noReviewLink_requestStaysPending_noSms() {
        // Twilio connection WITHOUT a reviewLink (or no connection at all).
        connections.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of("accountSid", "AC", "authToken", "t", "fromNumber", "+1")))
                .config(new HashMap<>())
                .build()).block();
        UUID contactId = seedContact(PHONE, false);
        UUID reqId = seedDueRequest(contactId, UUID.randomUUID());

        sender.sendDueOnce().block();

        ReviewRequest after = reviewRequests.findByTenantIdAndId(tenantId, reqId).block();
        assertThat(after.getStatus()).isEqualTo(ReviewRequest.Status.PENDING);
        assertThat(smsTo).isEmpty();
    }

    // -------------------------------------------------------------------------
    // Frequency cap -> a second request for the same contact in the window is skipped
    // -------------------------------------------------------------------------

    @Test
    void frequencyCap_secondRequestForSameContactSkipped() {
        seedTwilioWithReviewLink();
        UUID contactId = seedContact(PHONE, false);
        // Two due requests for the SAME contact, different subjects (so both rows are distinct).
        UUID req1 = seedDueRequest(contactId, UUID.randomUUID());
        UUID req2 = seedDueRequest(contactId, UUID.randomUUID());

        sender.sendDueOnce().block();

        // Exactly one SENT (the cap is 1 per window); the other SKIPPED by the frequency cap.
        long sent = List.of(req1, req2).stream()
                .map(id -> reviewRequests.findByTenantIdAndId(tenantId, id).block())
                .filter(r -> r.getStatus() == ReviewRequest.Status.SENT)
                .count();
        long skipped = List.of(req1, req2).stream()
                .map(id -> reviewRequests.findByTenantIdAndId(tenantId, id).block())
                .filter(r -> r.getStatus() == ReviewRequest.Status.SKIPPED)
                .count();
        assertThat(sent).isEqualTo(1);
        assertThat(skipped).isEqualTo(1);
        assertThat(smsTo).hasSize(1);
    }
}
