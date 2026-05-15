package com.kumouri.kmodigipresbe.integration.square;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.PaymentRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Square POS integration auto-configuration. Loaded only when
 * {@code kmosf.integrations.square.enabled=true}.
 *
 * <p>A tenant can have Square connected independently of the salon-spa module
 * — the connection's presence in {@code IntegrationConnection} (provider
 * {@code "square"}) is the runtime authority. The module gate
 * ({@code kmosf.modules.salon-spa.enabled}) controls the booking/loyalty
 * surfaces; this gate controls POS sync.
 *
 * <p>Error code range: 3000–3099 (Square).
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.integrations.square", name = "enabled")
@EnableConfigurationProperties(SquareProperties.class)
public class SquareAutoConfiguration {

    @Bean
    public SquareOAuthService squareOAuthService(SquareProperties props,
                                                  IntegrationConnectionRepository connections,
                                                  WebClient.Builder webClientBuilder,
                                                  ObjectMapper objectMapper) {
        return new SquareOAuthService(props, connections, webClientBuilder, objectMapper);
    }

    @Bean
    public SquarePosService squarePosService(InvoiceRepository invoices,
                                              PaymentRepository payments,
                                              ContactRepository contacts,
                                              DomainEventPublisher events) {
        return new SquarePosService(invoices, payments, contacts, events);
    }

    @Bean
    public SquareWebhookService squareWebhookService(ObjectMapper objectMapper,
                                                      IntegrationConnectionRepository connections,
                                                      SquarePosService posService,
                                                      SquareProperties props,
                                                      ReactiveMongoTemplate mongo) {
        return new SquareWebhookService(objectMapper, connections, posService, props, mongo);
    }
}
