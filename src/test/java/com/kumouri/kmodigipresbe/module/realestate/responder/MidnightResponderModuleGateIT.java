package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
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

import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T3 (Real Estate "Midnight Responder") — module-gate composition. The T3 beans require BOTH the
 * realestate module ({@code @ConditionalOnProperty(kmosf.modules.realestate.enabled)}) AND the responder
 * module (proxied by {@code @ConditionalOnBean(DefaultHandoffIntentHandler.class)}). The
 * {@code FieldServiceModuleToggleIT} bean-presence pattern, across three contexts.
 */
class MidnightResponderModuleGateIT {

    /** realestate OFF (default) → no T3 beans (and no realestate concierge at all). */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    static class RealEstateOff {
        @Autowired ApplicationContext ctx;

        @Test
        void t3BeansAbsent_whenRealEstateOff() {
            assertThatThrownBy(() -> ctx.getBean(TierRoutingService.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(ResponderHandoffDelegate.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** realestate ON + responder OFF → no DefaultHandoffIntentHandler → no T3 beans. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.realestate.enabled=true",
            "kmosf.modules.responder.enabled=false"
    })
    static class ResponderOff {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;

        @Test
        void t3BeansAbsent_whenResponderOff() {
            assertThatThrownBy(() -> ctx.getBean(TierRoutingService.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(ResponderHandoffDelegate.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /** realestate ON + responder ON (default) → the T3 beans are present + wired. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = "kmosf.modules.realestate.enabled=true")
    static class BothOn {
        @Autowired ApplicationContext ctx;
        @MockitoBean TwilioSmsService twilioSmsService;

        @Test
        void t3BeansPresent_whenBothOn() {
            assertThat(ctx.getBean(TierRoutingService.class)).isNotNull();
            assertThat(ctx.getBean(ResponderHandoffDelegate.class)).isNotNull();
            assertThat(ctx.getBean(RealEstateMidnightAutoConfiguration.MidnightResponderHandoffWiring.class))
                    .isNotNull();
        }
    }
}
