package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
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
 * T7 (Health "RescheduleFlow") — module-gate composition. The T7 beans require BOTH the frontdesk module
 * ({@code @ConditionalOnProperty(kmosf.modules.frontdesk.enabled)}) AND the waitlist engine (proxied by
 * {@code @ConditionalOnBean(WaitlistClaimEngine.class)} — the E4 engine beans exist only when
 * {@code kmosf.modules.waitlist.enabled}). The {@code SwitchboardModuleGateIT} pattern, across three contexts.
 */
class RescheduleFlowModuleGateIT {

    /** frontdesk OFF (default) → no T7 beans (and no controller registered). */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    static class FrontDeskOff {
        @Autowired ApplicationContext ctx;

        @Test
        void t7BeansAbsent_whenFrontDeskOff() {
            assertThatThrownBy(() -> ctx.getBean(FrontDeskSlotMaterializer.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(RescheduleGapFillSubscriber.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(RescheduleWaitlistIntentHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(RescheduleController.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** frontdesk ON + waitlist OFF → no WaitlistClaimEngine → no T7 beans. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.frontdesk.enabled=true",
            "kmosf.modules.waitlist.enabled=false"
    })
    static class WaitlistOff {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;

        @Test
        void t7BeansAbsent_whenWaitlistOff() {
            assertThatThrownBy(() -> ctx.getBean(FrontDeskSlotMaterializer.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(RescheduleGapFillSubscriber.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(RescheduleWaitlistIntentHandler.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** frontdesk ON + waitlist ON → the T7 beans are present + the controller is registered. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.frontdesk.enabled=true",
            "kmosf.modules.waitlist.enabled=true"
    })
    static class BothOn {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;

        @Test
        void t7BeansPresent_whenBothOn() {
            assertThat(ctx.getBean(FrontDeskSlotMaterializer.class)).isNotNull();
            assertThat(ctx.getBean(RescheduleGapFillSubscriber.class)).isNotNull();
            assertThat(ctx.getBean(RescheduleWaitlistIntentHandler.class)).isNotNull();
            assertThat(ctx.getBean(RescheduleAnalyticsService.class)).isNotNull();
            assertThat(ctx.getBean(RescheduleController.class)).isNotNull();
        }
    }
}
