package com.kumouri.kmodigipresbe.module.homeservices.integration.quickbooks;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.service.billing.InvoiceService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

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
 * loads it only when the QBO gate is on.
 *
 * <p>Beans are declared explicitly via {@code @Bean} rather than via a
 * {@code @ComponentScan}: classes in this package carry no {@code @Service} /
 * {@code @Component} stereotypes, so the main app's component scan does not
 * pick them up when the QBO gate is off (which would otherwise fail because
 * {@link QuickBooksProperties} isn't registered without this auto-config).
 * Controllers in this package carry their own {@code @ConditionalOnProperty}
 * to opt out of the main scan when the gate is off.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.integrations.quickbooks", name = "enabled")
@EnableConfigurationProperties(QuickBooksProperties.class)
public class QuickBooksAutoConfiguration {

    @Bean
    public QuickBooksOAuthService quickBooksOAuthService(QuickBooksProperties props,
                                                        IntegrationConnectionRepository connections,
                                                        WebClient.Builder webClientBuilder,
                                                        ObjectMapper objectMapper) {
        return new QuickBooksOAuthService(props, connections, webClientBuilder, objectMapper);
    }

    @Bean
    public QuickBooksInvoiceSync quickBooksInvoiceSync(DomainEventPublisher events,
                                                      IntegrationConnectionRepository connections,
                                                      InvoiceRepository invoices,
                                                      QuickBooksOAuthService oauth,
                                                      WebClient.Builder webClientBuilder,
                                                      ObjectMapper objectMapper,
                                                      QuickBooksProperties props,
                                                      CircuitBreakerRegistry breakers) {
        return new QuickBooksInvoiceSync(
                events, connections, invoices, oauth,
                webClientBuilder, objectMapper, props, breakers);
    }

    @Bean
    public QuickBooksWebhookService quickBooksWebhookService(ObjectMapper objectMapper,
                                                             IntegrationConnectionRepository connections,
                                                             InvoiceRepository invoices,
                                                             InvoiceService invoiceService) {
        return new QuickBooksWebhookService(objectMapper, connections, invoices, invoiceService);
    }
}
