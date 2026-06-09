package com.kumouri.kmodigipresbe.module.realestate.nurture;

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
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * T1 — reply→book: a positive reply (the E2 {@code RealEstateNurtureReplyHandler}) exits the contact's
 * active nurture enrollment and offers a showing via the booking-link SMS path (the UNCHANGED
 * {@code NurtureReplyService.handlePositiveReplyByPhone}; no live Cal.com). Proves:
 * <ul>
 *   <li>{@code supports("realestate", "POSITIVE_REPLY")} is true (and false for a non-RE vertical / a
 *       non-positive intent);</li>
 *   <li>a positive reply for an enrolled buyer phone → enrollment advances to BOOKED + a booking-link SMS
 *       is sent + {@code NURTURE_POSITIVE_REPLY} / {@code NURTURE_BOOKING_LINK_SENT} fire;</li>
 *   <li>a phone with no active enrollment → {@code ignored()} (4310 swallowed), zero send.</li>
 * </ul>
 * The realestate + nurture modules are on so the handler bean exists. Twilio is a {@code @MockitoBean}
 * capture seam (no live send — §7).
 */
@SpringBootTest
@org.springframework.context.annotation.Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true"
})
class RealEstateNurtureReplyBookIT {

    private static final String BUYER = "+12145550200";
    private static final String BOOKING_LINK = "https://cal.example/gateway/showing";

    @Autowired ReactiveMongoTemplate mongo;
    @Autowired RealEstateNurtureReplyHandler handler;
    @Autowired NurtureEnrollmentRepository enrollments;
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
        mongo.remove(new Query(), NurtureEnrollment.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        smsTo.clear();
        smsBodies.clear();

        when(twilioSmsService.sendSms(any(SmsCommunicationRequest.class))).thenAnswer(inv -> {
            SmsCommunicationRequest req = inv.getArgument(0);
            smsTo.add(req.to() == null ? null : req.to().e164());
            smsBodies.add(req.body());
            return Mono.just(true);
        });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder()
                .id(tenantId).slug("re-nurture-reply-it-" + tenantId)
                .displayName("Gateway Realty Reply IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        // The per-tenant booking link the reply→book SMS sends.
        connections.save(IntegrationConnection.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).provider(TwilioSmsService.PROVIDER)
                .config(new HashMap<>(Map.of("bookingLink", BOOKING_LINK)))
                .build()).block();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    private UUID seedEnrolledBuyer() {
        UUID cid = UUID.randomUUID();
        mongo.save(Contact.builder()
                .id(cid).tenantId(tenantId).type(ContactType.PERSON)
                .firstName("Buyer").displayName("Buyer")
                .phones(List.of(PhoneNumber.builder().number(BUYER).label("mobile").build()))
                .build()).block();
        UUID campaignId = UUID.randomUUID();
        mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(campaignId).contactId(cid)
                .bucket(DormancyBucket.A)
                .currentStepIndex(1)
                .status(NurtureEnrollmentStatus.ACTIVE)
                .enrolledAt(java.time.Instant.now())
                .build()).block();
        return cid;
    }

    private IntentHandler.HandlerContext ctx(String intent) {
        ConversationState state = ConversationState.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).phone(BUYER).vertical("realestate")
                .build();
        return new IntentHandler.HandlerContext(
                tenantId, BUYER, "+12145559999", "YES let's chat",
                new IntentClassification(intent, 0.95, Map.of()), state);
    }

    @Test
    void supports_realestatePositiveIntent_only() {
        assertThat(handler.supports("realestate", "POSITIVE_REPLY")).isTrue();
        assertThat(handler.supports("realestate", "yes")).isTrue();           // case-insensitive
        assertThat(handler.supports("realestate", "NOT_INTERESTED")).isFalse();
        assertThat(handler.supports("salon", "POSITIVE_REPLY")).isFalse();    // wrong vertical
        assertThat(handler.supports("realestate", null)).isFalse();
    }

    @Test
    void positiveReply_exitsEnrollment_sendsBookingLink_andFiresEvents() {
        UUID buyer = seedEnrolledBuyer();

        IntentHandler.HandlerResult result = handler.handle(ctx("POSITIVE_REPLY")).block();

        // Handled, no router reply (NurtureReplyService sent the booking link itself — no double-text).
        assertThat(result).isNotNull();
        assertThat(result.handled()).isTrue();
        assertThat(result.replyText()).isNull();

        // The enrollment advanced to BOOKED (REPLIED → booking-link sent → BOOKED).
        NurtureEnrollment enr = enrollments.findAllByTenantIdAndContactIdAndStatusInOrderByEnrolledAtDesc(
                        tenantId, buyer, List.of(NurtureEnrollmentStatus.BOOKED)).blockFirst();
        assertThat(enr).isNotNull();
        assertThat(enr.getStatus()).isEqualTo(NurtureEnrollmentStatus.BOOKED);

        // The booking-link SMS went to the buyer and carries the configured link.
        assertThat(smsTo).contains(BUYER);
        assertThat(smsBodies).anySatisfy(b -> assertThat(b).contains(BOOKING_LINK));

        // Both advisory events fired.
        assertThat(observed).filteredOn(e -> DomainEventType.NURTURE_POSITIVE_REPLY.equals(e.type()))
                .hasSize(1);
        assertThat(observed).filteredOn(e -> DomainEventType.NURTURE_BOOKING_LINK_SENT.equals(e.type()))
                .hasSize(1);
    }

    @Test
    void noActiveEnrollment_isIgnored_zeroSend() {
        // No enrollment seeded for BUYER → handlePositiveReplyByPhone 4310 → swallowed to ignored().
        IntentHandler.HandlerResult result = handler.handle(ctx("POSITIVE_REPLY")).block();

        assertThat(result).isNotNull();
        assertThat(result.handled()).isFalse();
        assertThat(smsTo).isEmpty();
    }
}
