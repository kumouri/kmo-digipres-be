package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T5 — module-gate composition. The T5 callback handler requires BOTH the home-services module
 * ({@code @ConditionalOnProperty(kmosf.modules.home-services.enabled)}) AND the responder module (proxied
 * by {@code @ConditionalOnBean(InboundIntentRouter.class)}); the offer subscriber additionally requires the
 * default-OFF {@code kmosf.modules.home-callback-offer.enabled}. The {@code SwitchboardModuleGateIT}
 * bean-presence pattern across four contexts.
 */
class CallbackModuleGateIT {

    /** home-services OFF (default) → no T5 handler bean. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    static class HomeServicesOff {
        @Autowired ApplicationContext ctx;

        @Test
        void handlerAbsent_whenHomeServicesOff() {
            assertThatThrownBy(() -> ctx.getBean(CallbackIntentHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(CallbackOfferSubscriber.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** home-services ON + responder OFF → no InboundIntentRouter → no T5 handler bean. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.home-services.enabled=true",
            "kmosf.modules.responder.enabled=false"
    })
    static class ResponderOff {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;
        @MockitoBean EmailService emailService;

        @Test
        void handlerAbsent_whenResponderOff() {
            assertThatThrownBy(() -> ctx.getBean(CallbackIntentHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** both ON, offer flag absent (default-OFF) → handler present, offer subscriber ABSENT. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = "kmosf.modules.home-services.enabled=true")
    static class BothOnOfferOff {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;
        @MockitoBean EmailService emailService;

        @Test
        void handlerPresent_offerSubscriberAbsent_whenOfferFlagDefault() {
            assertThat(ctx.getBean(CallbackIntentHandler.class)).isNotNull();
            assertThat(ctx.getBean(CallbackController.class)).isNotNull();
            assertThatThrownBy(() -> ctx.getBean(CallbackOfferSubscriber.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** both ON + offer flag ON → the handler AND the offer subscriber are present. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.home-services.enabled=true",
            "kmosf.modules.home-callback-offer.enabled=true"
    })
    static class BothOnOfferOn {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;
        @MockitoBean EmailService emailService;

        @Test
        void handlerAndOfferSubscriberPresent_whenOfferFlagOn() {
            assertThat(ctx.getBean(CallbackIntentHandler.class)).isNotNull();
            assertThat(ctx.getBean(CallbackOfferSubscriber.class)).isNotNull();
        }
    }
}
