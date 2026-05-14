package com.kumouri.kmodigipresbe.integration.postmark;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagement;
import com.kumouri.kmodigipresbe.model.communication.EmailEngagementEvent;
import com.kumouri.kmodigipresbe.repository.EmailEngagementRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end: tenant has a Postmark IntegrationConnection with a webhook Basic-Auth
 * password; an inbound webhook with valid Basic Auth creates an
 * {@link EmailEngagement} record under the tenant; cross-tenant slug is rejected.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PostmarkWebhookIT {

    @Autowired WebTestClient web;
    @Autowired IntegrationConnectionRepository connections;
    @Autowired EmailEngagementRepository engagements;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        // Wipe what we touch — the Mongo container is shared across IT classes.
        mongo.remove(new Query(), IntegrationConnection.class).block();
        mongo.remove(new Query(), EmailEngagement.class).block();

        tenantId = UUID.randomUUID();
        TenantContext bootstrap = new TenantContext(tenantId, UUID.randomUUID(), Set.of("ADMIN"));
        connections.save(IntegrationConnection.builder()
                        .tenantId(tenantId)
                        .provider("postmark")
                        .secrets(Map.of("webhookBasicAuthPassword", "s3cret"))
                        .build())
                .contextWrite(TenantContextHolder.write(bootstrap))
                .block();
    }

    private String basic(String user, String pass) {
        return "Basic " + Base64.getEncoder().encodeToString(
                (user + ":" + pass).getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void validOpenWebhook_persistsEngagementAndFiresEvent() {
        UUID contactId = UUID.randomUUID();
        String body = "{"
                + "\"RecordType\":\"Open\","
                + "\"MessageID\":\"mid-1\","
                + "\"Recipient\":\"alice@example.test\","
                + "\"ReceivedAt\":\"2026-05-14T20:00:00.000Z\","
                + "\"Metadata\":{\"kmosf_contact_id\":\"" + contactId + "\"}"
                + "}";

        web.post().uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .header("Authorization", basic("postmark", "s3cret"))
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().is2xxSuccessful();

        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
        List<EmailEngagement> rows = engagements
                .findAllByTenantIdAndContactIdOrderByEventAtDesc(tenantId, contactId)
                .contextWrite(TenantContextHolder.write(ctx))
                .collectList()
                .block();
        assertThat(rows).isNotNull().hasSize(1);
        EmailEngagement evt = rows.get(0);
        assertThat(evt.getEvent()).isEqualTo(EmailEngagementEvent.OPEN);
        assertThat(evt.getMessageId()).isEqualTo("mid-1");
        assertThat(evt.getRecipient()).isEqualTo("alice@example.test");
    }

    @Test
    void wrongBasicAuthPassword_returns401_andDoesNotPersist() {
        String body = "{\"RecordType\":\"Open\",\"MessageID\":\"x\",\"Recipient\":\"a@b.c\"}";
        web.post().uri("/public/integrations/postmark/" + tenantId + "/webhook")
                .header("Authorization", basic("postmark", "wrong"))
                .header("Content-Type", "application/json")
                .bodyValue(body)
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1704);

        TenantContext ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));
        Long count = engagements.count()
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(count).isZero();
    }

    @Test
    void unknownTenantSlug_returns404() {
        UUID unknown = UUID.randomUUID();
        web.post().uri("/public/integrations/postmark/" + unknown + "/webhook")
                .header("Authorization", basic("postmark", "s3cret"))
                .header("Content-Type", "application/json")
                .bodyValue("{\"RecordType\":\"Open\"}")
                .exchange()
                .expectStatus().isNotFound()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(1702);
    }
}
