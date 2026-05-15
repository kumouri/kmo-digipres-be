package com.kumouri.kmodigipresbe.automation;

import com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscriptionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.TicketRepository;
import com.kumouri.kmodigipresbe.service.template.SmsTemplateRegistry;
import com.kumouri.kmodigipresbe.service.template.TemplatedEmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 10e — focused unit coverage of the {@code SEND_SMS} branch in
 * {@link RuleActionDispatcher}. The send happens via {@link TwilioSmsService}
 * and is skipped (not thrown) when the payload field is missing or
 * non-E.164 — this is the single most likely failure mode in production
 * (event payloads with null phones) and we need to verify it never poisons
 * the rule-engine's action-list.
 */
class RuleActionDispatcherSendSmsTest {

    private TwilioSmsService twilio;
    private SmsTemplateRegistry registry;
    private RuleActionDispatcher dispatcher;

    @BeforeEach
    void setup() {
        twilio = mock(TwilioSmsService.class);
        registry = new SmsTemplateRegistry();
        when(twilio.sendSms(any(SmsCommunicationRequest.class))).thenReturn(Mono.just(true));
        dispatcher = new RuleActionDispatcher(
                mock(TemplatedEmailService.class),
                mock(ActivityRepository.class),
                mock(WebhookSubscriptionRepository.class),
                mock(WebhookDeliveryService.class),
                twilio,
                registry,
                mock(TicketRepository.class));
    }

    @Test
    void validPayload_invokesTwilioWithRenderedTemplate() {
        RuleAction action = RuleAction.builder()
                .type(RuleAction.ActionType.SEND_SMS)
                .params(Map.of(
                        "templateName", "on-the-way-default",
                        "toPhoneField", "contactPhoneE164"))
                .build();
        DomainEvent event = new DomainEvent(
                DomainEventType.WORK_ORDER_EN_ROUTE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Map.of("contactPhoneE164", "+15555550100"),
                Instant.now());

        dispatcher.dispatch(action, event).block();

        ArgumentCaptor<SmsCommunicationRequest> captor =
                ArgumentCaptor.forClass(SmsCommunicationRequest.class);
        verify(twilio, times(1)).sendSms(captor.capture());
        SmsCommunicationRequest sent = captor.getValue();
        assertThat(sent.to().e164()).isEqualTo("+15555550100");
        assertThat(sent.body()).contains("technician is on the way");
    }

    @Test
    void missingPhoneField_skipsWithoutThrowing() {
        RuleAction action = RuleAction.builder()
                .type(RuleAction.ActionType.SEND_SMS)
                .params(Map.of(
                        "templateName", "on-the-way-default",
                        "toPhoneField", "contactPhoneE164"))
                .build();
        DomainEvent event = new DomainEvent(
                DomainEventType.WORK_ORDER_EN_ROUTE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Map.of(),  // payload missing the field
                Instant.now());

        // Must complete successfully (no exception, no Twilio call).
        dispatcher.dispatch(action, event).block();

        verify(twilio, never()).sendSms(any());
    }

    @Test
    void nonE164PhoneValue_skipsWithoutThrowing() {
        RuleAction action = RuleAction.builder()
                .type(RuleAction.ActionType.SEND_SMS)
                .params(Map.of(
                        "templateName", "on-the-way-default",
                        "toPhoneField", "contactPhoneE164"))
                .build();
        DomainEvent event = new DomainEvent(
                DomainEventType.WORK_ORDER_EN_ROUTE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Map.of("contactPhoneE164", "555-0100"),  // not E.164
                Instant.now());

        dispatcher.dispatch(action, event).block();

        verify(twilio, never()).sendSms(any());
    }

    @Test
    void missingTemplateName_skipsWithoutThrowing() {
        RuleAction action = RuleAction.builder()
                .type(RuleAction.ActionType.SEND_SMS)
                .params(Map.of("toPhoneField", "contactPhoneE164"))
                .build();
        DomainEvent event = new DomainEvent(
                DomainEventType.WORK_ORDER_EN_ROUTE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Map.of("contactPhoneE164", "+15555550100"),
                Instant.now());

        dispatcher.dispatch(action, event).block();

        verify(twilio, never()).sendSms(any());
    }

    @Test
    void unregisteredTemplate_fallsBackToLiteralString() {
        RuleAction action = RuleAction.builder()
                .type(RuleAction.ActionType.SEND_SMS)
                .params(Map.of(
                        "templateName", "Custom literal: please call us back.",
                        "toPhoneField", "contactPhoneE164"))
                .build();
        DomainEvent event = new DomainEvent(
                DomainEventType.WORK_ORDER_EN_ROUTE,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Map.of("contactPhoneE164", "+15555550100"),
                Instant.now());

        dispatcher.dispatch(action, event).block();

        ArgumentCaptor<SmsCommunicationRequest> captor =
                ArgumentCaptor.forClass(SmsCommunicationRequest.class);
        verify(twilio, times(1)).sendSms(captor.capture());
        assertThat(captor.getValue().body())
                .isEqualTo("Custom literal: please call us back.");
    }
}
