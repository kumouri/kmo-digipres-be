package com.kumouri.kmodigipresbe.integration.gbp;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link GbpProperties} (NMM GBP review-reply automation). No
 * {@code @ConditionalOnProperty} gate here: {@code GbpApiClient} is a plain {@code @Service}
 * reused by both the default-OFF {@code GbpReviewPoller} and the always-registerable admin
 * approve/post controller, so it needs {@link GbpProperties} unconditionally. The codebase has no
 * {@code @ConfigurationPropertiesScan}, so the binding is registered explicitly here (the
 * {@code DocumensoConfig} / {@code StripeConfig} precedent).
 */
@Configuration
@EnableConfigurationProperties(GbpProperties.class)
public class GbpConfig {
}
