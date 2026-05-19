package com.kumouri.kmodigipresbe.integration.calcom;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link CalComProperties} (Phase H — H.2). The Cal.com webhook
 * endpoint ({@code CalComWebhookController}) is always-on (it serves the
 * unauthenticated {@code /public/integrations/calcom/{tenantId}/webhook} route)
 * and {@code CalComClient} needs {@link CalComProperties} unconditionally. No
 * {@code @ConditionalOnProperty} gate mirrors the Stripe / Documenso precedent.
 * The codebase has no {@code @ConfigurationPropertiesScan}, so the binding is
 * registered explicitly here (the {@code StripeConfig} / {@code FileStorageConfig}
 * precedent).
 */
@Configuration
@EnableConfigurationProperties(CalComProperties.class)
public class CalComConfig {
}
