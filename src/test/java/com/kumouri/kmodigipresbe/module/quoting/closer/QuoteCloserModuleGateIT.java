package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T11 (Home "QuoteCloser") — module-gate composition. The QuoteCloser beans require BOTH the quoting module
 * ({@code @ConditionalOnProperty(kmosf.modules.quoting.enabled)}, default-OFF) AND the nurture module
 * (proxied by {@code @ConditionalOnBean(NurtureMessageComposer.class)}). The
 * {@code MidnightResponderModuleGateIT} (T3) bean-presence pattern, across three contexts.
 */
class QuoteCloserModuleGateIT {

    /** quoting OFF (default) → no QuoteCloser beans (the whole composition is absent). */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.quoting.enabled=false",
            "kmosf.quartz.proof-job.enabled=false",
            "kmosf.recurring-invoice.spawn-job.enabled=false"
    })
    static class QuotingOff {
        @Autowired ApplicationContext ctx;

        @Test
        void quoteCloserBeansAbsent_whenQuotingOff() {
            assertThatThrownBy(() -> ctx.getBean(QuoteWonSubscriber.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(QuoteCloserAnalyticsService.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            // The both-module config controller is also absent (gated @ConditionalOnProperty(quoting)).
            assertThatThrownBy(() -> ctx.getBean(QuoteCloserConfigController.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }

    /**
     * quoting ON + nurture OFF → no {@code NurtureMessageComposer} → the ACTIVE composition glue (the
     * {@code QUOTE_ACCEPTED} subscriber + the enrollment job) is absent (the both-module gate). The
     * quoting-only READ surface (the analytics service + the controllers) still loads — and the controllers
     * enforce the both-module requirement per-tenant at request time (a 1132 on the endpoint), NOT a
     * context-load failure. The context MUST load (no unsatisfied-dependency crash).
     */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.quoting.enabled=true",
            "kmosf.modules.nurture.enabled=false",
            "kmosf.quartz.proof-job.enabled=false",
            "kmosf.recurring-invoice.spawn-job.enabled=false"
    })
    static class NurtureOff {
        @Autowired ApplicationContext ctx;

        @Test
        void activeGlueAbsent_butReadSurfaceLoads_whenNurtureOff() {
            // The active composition glue is gated on BOTH modules → absent.
            assertThatThrownBy(() -> ctx.getBean(QuoteWonSubscriber.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            assertThatThrownBy(() -> ctx.getBean(QuoteCloserEnrollmentJob.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
            // The quoting-only read surface still loads (so the context does NOT fail to load).
            assertThat(ctx.getBean(QuoteCloserAnalyticsService.class)).isNotNull();
            assertThat(ctx.getBean(QuoteCloserController.class)).isNotNull();
            assertThat(ctx.getBean(QuoteCloserConfigController.class)).isNotNull();
        }
    }

    /** quoting ON + nurture ON (default) → the QuoteCloser composition beans are present + wired. */
    @SpringBootTest
    @Import(TestcontainersConfiguration.class)
    @ExtendWith(SpringExtension.class)
    @TestPropertySource(properties = {
            "kmosf.modules.quoting.enabled=true",
            "kmosf.modules.nurture.enabled=true",
            "kmosf.quartz.proof-job.enabled=false",
            "kmosf.recurring-invoice.spawn-job.enabled=false"
    })
    static class BothOn {
        @Autowired ApplicationContext ctx;

        @Test
        void quoteCloserBeansPresent_whenBothOn() {
            assertThat(ctx.getBean(QuoteWonSubscriber.class)).isNotNull();
            assertThat(ctx.getBean(QuoteCloserAnalyticsService.class)).isNotNull();
            // The controllers (component-scanned, quoting-gated) are present too.
            assertThat(ctx.getBean(QuoteCloserConfigController.class)).isNotNull();
            assertThat(ctx.getBean(QuoteCloserController.class)).isNotNull();
            // The enrollment job stays ABSENT unless quote-closer-job is opted in (default-OFF).
            assertThatThrownBy(() -> ctx.getBean(QuoteCloserEnrollmentJob.class))
                    .isInstanceOf(NoSuchBeanDefinitionException.class);
        }
    }
}
