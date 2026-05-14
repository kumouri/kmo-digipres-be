package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.ComponentScan;

/**
 * QuickBooks Online auto-configuration. Loaded only when
 * {@code kmosf.integrations.quickbooks.enabled=true}.
 *
 * <p>This module is <strong>independent of</strong>
 * {@link com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration}
 * — a tenant can have QBO connected without enabling the home-services vertical
 * (e.g. a generic CRM tenant using KMOSF's invoicing surface). Per-tenant
 * runtime enablement is via
 * {@link com.kumouri.kmodigipresbe.integration.IntegrationConnection} (provider
 * {@code "quickbooks"}); the connection's presence is the authority. A missing
 * connection for a tenant is treated as "QBO not connected, skip" rather than
 * an error.
 *
 * <p>Lives under {@code module/homeservices/} for filesystem locality with the
 * home-services vertical that consumes QBO most heavily, but the auto-config
 * registration in {@code AutoConfiguration.imports} is independent — Spring
 * loads it regardless of the {@code home-services} gate.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.integrations.quickbooks", name = "enabled")
@EnableConfigurationProperties(QuickBooksProperties.class)
@ComponentScan(basePackageClasses = QuickBooksAutoConfiguration.class)
public class QuickBooksAutoConfiguration {
}
