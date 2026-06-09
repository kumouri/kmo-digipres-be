package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

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
 * T4 (Health "Switchboard AI") — module-gate composition. The T4 beans require BOTH the frontdesk module
 * ({@code @ConditionalOnProperty(kmosf.modules.frontdesk.enabled)}) AND the responder module (proxied by
 * {@code @ConditionalOnBean(InboundIntentRouter.class)}). The {@code MidnightResponderModuleGateIT}
 * bean-presence pattern, across three contexts.
 */
class SwitchboardModuleGateIT {

    /** frontdesk OFF (default) → no T4 beans. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    static class FrontDeskOff {
        @Autowired ApplicationContext ctx;

        @Test
        void t4BeansAbsent_whenFrontDeskOff() {
            assertThatThrownBy(() -> ctx.getBean(LogisticsIntentHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(ClinicalTripwireHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(SwitchboardController.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** frontdesk ON + responder OFF → no InboundIntentRouter → no T4 beans. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.frontdesk.enabled=true",
            "kmosf.modules.responder.enabled=false"
    })
    static class ResponderOff {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;
        @MockitoBean EmailService emailService;

        @Test
        void t4BeansAbsent_whenResponderOff() {
            assertThatThrownBy(() -> ctx.getBean(LogisticsIntentHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(ClinicalTripwireHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(SwitchboardDeflectionRecorder.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** frontdesk ON + responder ON (default) → the T4 beans are present + the controller is registered. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = "kmosf.modules.frontdesk.enabled=true")
    static class BothOn {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;
        @MockitoBean EmailService emailService;

        @Test
        void t4BeansPresent_whenBothOn() {
            assertThat(ctx.getBean(LogisticsIntentHandler.class)).isNotNull();
            assertThat(ctx.getBean(ClinicalTripwireHandler.class)).isNotNull();
            assertThat(ctx.getBean(SwitchboardDeflectionService.class)).isNotNull();
            assertThat(ctx.getBean(SwitchboardDeflectionRecorder.class)).isNotNull();
            assertThat(ctx.getBean(SwitchboardController.class)).isNotNull();
        }
    }
}
