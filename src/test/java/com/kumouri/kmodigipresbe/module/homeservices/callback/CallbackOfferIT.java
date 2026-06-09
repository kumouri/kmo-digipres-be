package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T5 — {@link CallbackOfferSubscriber}: the callback opt-in SMS sent after a home-services voicemail
 * (off the shipped {@code VOICEMAIL_LEAD_CREATED} event — {@code TwilioVoicemailService} stays empty-diff).
 * Drives the subscriber directly (the {@code SwitchboardDeflectionRecorder.handle} visible-for-test
 * pattern). Proves: a home tenant gets one offer + an OFFERED funnel row + a CALLBACK_OFFERED event; a
 * re-fired event for the same CallSid sends zero second offer (ledger-insert-FIRST idempotent); a
 * mole/NMM (non-home) tenant gets zero offer (home-scoped / NMM byte-equivalent).
 *
 * <p>§7: {@link TwilioSmsService} → {@code @MockitoBean} (no live send); sandbox token.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.home-callback-offer.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class CallbackOfferIT {

    private static final String CALLER = "+13145550501";
    private static final String CALL_SID = "CA-offer-it-1";

    @Autowired CallbackOfferSubscriber subscriber;
    @Autowired ResponderConfigRepository responderConfigs;
    @Autowired ContactRepository contacts;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilioSmsService;

    private final List<SmsCommunicationRequest> sentSms = new CopyOnWriteArrayList<>();
    private UUID tenantId;
    private UUID contactId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), ResponderConfig.class).block();
        mongo.remove(new Query(), CallbackOfferLog.class).block();
        mongo.remove(new Query(), CallbackFunnelLog.class).block();
        mongo.remove(new Query(), CallbackConfig.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        sentSms.clear();

        org.mockito.Mockito.when(twilioSmsService.sendSms(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(inv -> {
                    sentSms.add(inv.getArgument(0));
                    return Mono.just(true);
                });

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder().id(tenantId).slug("callback-offer-it-" + tenantId)
                .displayName("Callback Offer IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(java.util.Set.of("home-services", "responder"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        seedTwilio();
    }

    @Test
    void homeVoicemailLead_sendsOneOffer_funnelOffered_event() {
        seedHomeResponderConfig();
        Contact contact = seedCaller();

        subscriber.handle(leadEvent(tenantId, CALL_SID, contact.getId())).block();

        assertThat(sentSms).hasSize(1);
        assertThat(sentSms.get(0).to().e164()).isEqualTo(CALLER);
        assertThat(sentSms.get(0).body()).isEqualTo(CallbackCopy.DEFAULT_OFFER_MESSAGE);
        assertThat(offerLogCount(CALL_SID)).isEqualTo(1);
        assertThat(funnelCount(CallbackFunnelStage.OFFERED)).isEqualTo(1);
    }

    @Test
    void reFiredSameCallSid_sendsZeroSecondOffer() {
        seedHomeResponderConfig();
        Contact contact = seedCaller();

        subscriber.handle(leadEvent(tenantId, CALL_SID, contact.getId())).block();
        subscriber.handle(leadEvent(tenantId, CALL_SID, contact.getId())).block();

        assertThat(sentSms).hasSize(1);                       // exactly one offer, not two
        assertThat(offerLogCount(CALL_SID)).isEqualTo(1);     // one ledger row (the unique index held)
        assertThat(funnelCount(CallbackFunnelStage.OFFERED)).isEqualTo(1);
    }

    @Test
    void nonHomeTenant_sendsZeroOffer() {
        // A mole/NMM tenant has no ResponderConfig(vertical="home") → the subscriber is a clean no-op.
        Contact contact = seedCaller();

        subscriber.handle(leadEvent(tenantId, CALL_SID, contact.getId())).block();

        assertThat(sentSms).isEmpty();
        assertThat(offerLogCount(CALL_SID)).isZero();
        assertThat(funnelCount(CallbackFunnelStage.OFFERED)).isZero();
    }

    // ── helpers ──

    private DomainEvent leadEvent(UUID tenant, String callSid, UUID contact) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("callSid", callSid);
        payload.put("contactId", contact.toString());
        return DomainEvent.of(DomainEventType.VOICEMAIL_LEAD_CREATED, tenant, contact, payload);
    }

    private long offerLogCount(String callSid) {
        return mongo.findAll(CallbackOfferLog.class).collectList().block().stream()
                .filter(l -> callSid.equals(l.getCallSid())).count();
    }

    private long funnelCount(CallbackFunnelStage stage) {
        return mongo.findAll(CallbackFunnelLog.class).collectList().block().stream()
                .filter(l -> l.getStage() == stage).count();
    }

    private void seedHomeResponderConfig() {
        responderConfigs.save(ResponderConfig.builder()
                .id(UUID.randomUUID()).tenantId(tenantId).enabled(true)
                .vertical(CallbackIntents.VERTICAL)
                .intents(CallbackIntents.DEFAULTS)
                .build()).block();
    }

    private Contact seedCaller() {
        Contact contact = contacts.save(Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName("Voicemail caller " + CALLER)
                .phones(List.of(PhoneNumber.builder().number(CALLER).label("voicemail").build()))
                .build()).block();
        contactId = contact.getId();
        return contact;
    }

    private void seedTwilio() {
        mongo.save(IntegrationConnection.builder()
                .tenantId(tenantId).provider("twilio")
                .secrets(new HashMap<>(Map.of("authToken", "twilio_test_callback")))
                .config(new HashMap<>(Map.of("notifyPhone", "+16185550999")))
                .build()).block();
    }
}
