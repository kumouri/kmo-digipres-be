package com.kumouri.kmodigipresbe.nurture;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureReplyService;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * E1 — NurtureReplyBookIT: drives {@code NurtureReplyService} (reply → exit → book). Proves a positive
 * reply exits the enrollment and (when a per-tenant Cal.com booking link is configured) sends the
 * booking-link SMS + marks BOOKED + fires both events; that a second reply is a 4311 no-op; that the
 * by-phone resolver finds the same enrollment; and that a missing booking link leaves the enrollment
 * REPLIED (no SMS). §7: the booking link is the per-tenant config value (NOT hardcoded) and the SMS is
 * the {@code @MockitoBean} seam — no live Cal.com call, no live send.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        // Runner stays OFF (default) — this IT exercises the reply service directly.
})
class NurtureReplyBookIT {

    private static final String PHONE = "+16185550123";
    private static final String BOOKING_LINK = "https://cal.com/nmm/showing";

    @Autowired ReactiveMongoTemplate mongo;
    @Autowired NurtureReplyService replyService;
    @Autowired NurtureEnrollmentRepository enrollments;
    @Autowired DomainEventPublisher eventPublisher;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<String> smsTo = new CopyOnWriteArrayList<>();
    private final List<String> smsBodies = new CopyOnWriteArrayList<>();

    private UUID tenantId;
    private UUID contactId;
    private List<DomainEvent> observed;
    private Disposable sub;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), NurtureEnrollment.class).block();
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
                .id(tenantId).slug("nurture-reply-it-" + tenantId)
                .displayName("Nurture Reply IT").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        Contact contact = mongo.save(Contact.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).type(ContactType.PERSON)
                .displayName("Replying Lead")
                .phones(List.of(PhoneNumber.builder().number(PHONE).label("mobile").build()))
                .build()).block();
        contactId = contact.getId();

        observed = new CopyOnWriteArrayList<>();
        sub = eventPublisher.stream().subscribe(observed::add);
    }

    @AfterEach
    void cleanup() {
        if (sub != null) sub.dispose();
    }

    private void seedTwilio(boolean withBookingLink) {
        Map<String, String> config = new HashMap<>();
        if (withBookingLink) {
            config.put("bookingLink", BOOKING_LINK);
        }
        mongo.save(IntegrationConnection.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of(
                        "accountSid", "ACtest", "authToken", "tok", "fromNumber", "+16185550000")))
                .config(config)
                .build()).block();
    }

    private NurtureEnrollment seedEnrollment(NurtureEnrollmentStatus status) {
        return mongo.save(NurtureEnrollment.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .campaignId(UUID.randomUUID()).contactId(contactId)
                .bucket(DormancyBucket.A)
                .currentStepIndex(1)
                .status(status)
                .build()).block();
    }

    @Test
    void positiveReply_withBookingLink_exitsAndBooks() {
        seedTwilio(true);
        NurtureEnrollment enr = seedEnrollment(NurtureEnrollmentStatus.ACTIVE);

        NurtureEnrollment result =
                replyService.handlePositiveReplyByEnrollment(tenantId, enr.getId()).block();

        assertThat(result.getStatus()).isEqualTo(NurtureEnrollmentStatus.BOOKED);
        assertThat(result.getRepliedAt()).isNotNull();
        // Booking-link SMS went out with the configured link.
        assertThat(smsTo).containsExactly(PHONE);
        assertThat(smsBodies).singleElement().asString().contains(BOOKING_LINK);
        // Both events fired.
        assertThat(observed).filteredOn(e -> DomainEventType.NURTURE_POSITIVE_REPLY.equals(e.type()))
                .hasSize(1);
        assertThat(observed).filteredOn(e -> DomainEventType.NURTURE_BOOKING_LINK_SENT.equals(e.type()))
                .hasSize(1);

        // The DB row reflects BOOKED.
        NurtureEnrollment persisted = enrollments.findByTenantIdAndId(tenantId, enr.getId()).block();
        assertThat(persisted.getStatus()).isEqualTo(NurtureEnrollmentStatus.BOOKED);
    }

    @Test
    void secondReply_isTerminalNoOp_4311() {
        seedTwilio(true);
        NurtureEnrollment enr = seedEnrollment(NurtureEnrollmentStatus.ACTIVE);
        replyService.handlePositiveReplyByEnrollment(tenantId, enr.getId()).block();
        smsTo.clear();

        Throwable t = catchThrowable(() ->
                replyService.handlePositiveReplyByEnrollment(tenantId, enr.getId()).block());

        assertThat(t).isInstanceOf(DigiPresBeException.class);
        assertThat(((DigiPresBeException) t).getErrorCode()).isEqualTo(4311);
        assertThat(smsTo).isEmpty(); // no second booking SMS
    }

    @Test
    void byPhone_resolvesNewestActiveEnrollment_andBooks() {
        seedTwilio(true);
        NurtureEnrollment enr = seedEnrollment(NurtureEnrollmentStatus.ENROLLED);

        NurtureEnrollment result =
                replyService.handlePositiveReplyByPhone(tenantId, PHONE).block();

        assertThat(result.getId()).isEqualTo(enr.getId());
        assertThat(result.getStatus()).isEqualTo(NurtureEnrollmentStatus.BOOKED);
        assertThat(smsTo).containsExactly(PHONE);
    }

    @Test
    void noBookingLink_staysReplied_noSms() {
        seedTwilio(false); // Twilio connected but no bookingLink configured
        NurtureEnrollment enr = seedEnrollment(NurtureEnrollmentStatus.ACTIVE);

        NurtureEnrollment result =
                replyService.handlePositiveReplyByEnrollment(tenantId, enr.getId()).block();

        assertThat(result.getStatus()).isEqualTo(NurtureEnrollmentStatus.REPLIED);
        assertThat(smsTo).isEmpty();
        assertThat(observed).filteredOn(e -> DomainEventType.NURTURE_POSITIVE_REPLY.equals(e.type()))
                .hasSize(1);
        assertThat(observed).filteredOn(e -> DomainEventType.NURTURE_BOOKING_LINK_SENT.equals(e.type()))
                .isEmpty();
    }

    @Test
    void unknownEnrollment_4310() {
        Throwable t = catchThrowable(() ->
                replyService.handlePositiveReplyByEnrollment(tenantId, UUID.randomUUID()).block());
        assertThat(t).isInstanceOf(DigiPresBeException.class);
        assertThat(((DigiPresBeException) t).getErrorCode()).isEqualTo(4310);
    }
}
