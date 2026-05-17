package com.kumouri.kmodigipresbe.integration.documenso;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link DocumensoProperties} (Phase F — F-D5). No
 * {@code @ConditionalOnProperty} gate here: the Documenso webhook
 * ({@code DocumensoWebhookService}) is always-on (it serves the unauthenticated
 * {@code /public/integrations/documenso/{tenantId}/webhook} route, exactly the
 * {@code StripeConfig} / {@code StripeWebhookController} precedent), and
 * {@code DocumensoClient} is a plain {@code @Service}; both need
 * {@link DocumensoProperties} unconditionally. The codebase has no
 * {@code @ConfigurationPropertiesScan}, so the binding is registered explicitly
 * here (the {@code FileStorageConfig} / {@code StripeConfig} precedent).
 */
@Configuration
@EnableConfigurationProperties(DocumensoProperties.class)
public class DocumensoConfig {
}
