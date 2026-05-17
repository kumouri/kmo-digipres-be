package com.kumouri.kmodigipresbe.integration.stripe;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link StripeProperties} (Phase E — E-D9). Unlike the QBO/Square
 * integrations there is no {@code @ConditionalOnProperty} gate here: the Stripe
 * webhook ({@code StripeWebhookService}) is always-on (it serves the existing
 * unauthenticated {@code /public/integrations/stripe/{tenantId}/webhook} route),
 * and {@code StripeCheckoutService} is a plain {@code @Service}; both need
 * {@link StripeProperties} unconditionally. The codebase has no
 * {@code @ConfigurationPropertiesScan}, so the binding is registered explicitly
 * here (the {@code FileStorageConfig} precedent).
 */
@Configuration
@EnableConfigurationProperties(StripeProperties.class)
public class StripeConfig {
}
