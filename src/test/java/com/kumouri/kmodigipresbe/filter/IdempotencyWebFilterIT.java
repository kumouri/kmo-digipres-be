package com.kumouri.kmodigipresbe.filter;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.IdempotencyKeyRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;

/**
 * AC-4: Idempotency filter correctness:
 * - Double-POST with same key returns cached 2xx (byte-identical)
 * - Exactly one idempotency_keys doc persisted
 * - Side effect (mocked mail send) fires exactly once
 * - Missing Idempotency-Key on annotated route -> 400/errorCode=3100
 * - Non-annotated POST with key -> not stored
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import({TestcontainersConfiguration.class, IdempotencyWebFilterIT.MockEmailConfig.class})
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false"
})
class IdempotencyWebFilterIT {

    @TestConfiguration(proxyBeanMethods = false)
    static class MockEmailConfig {
        @Bean
        @Primary
        EmailService mockEmailService() {
            EmailService mock = Mockito.mock(EmailService.class);
            Mockito.when(mock.sendSingleEmail(any()))
                    .thenReturn(Mono.just(true));
            return mock;
        }
    }

    @Autowired
    WebTestClient web;

    @Autowired
    TenantRepository tenants;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder encoder;

    @Autowired
    ReactiveMongoTemplate mongo;

    @Autowired
    IdempotencyKeyRepository idempotencyKeys;

    @Autowired
    EmailService emailService;

    private UUID tenantId;
    private String token;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        idempotencyKeys.deleteAll().block();
        Mockito.reset(emailService);
        Mockito.when(emailService.sendSingleEmail(any())).thenReturn(Mono.just(true));

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("idempotency-" + tenantId)
                .displayName("Idempotency Test")
                .status(Tenant.TenantStatus.ACTIVE)
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("idempotency@example.test")
                .passwordHash(encoder.encode("pass1234"))
                .displayName("IdempotencyUser")
                .roles(Set.of("STAFF", "ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        token = login("idempotency@example.test", "pass1234");
    }

    @Test
    void doublePostWithSameKeyReturnsIdenticalCachedResponse() {
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> emailBody = Map.of(
                "to", "recipient@example.com",
                "subject", "Test Subject",
                "body", "Test body"
        );

        // First POST
        byte[] firstResponse = web.post().uri("/communication/singleEmail")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(emailBody)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).returnResult().getResponseBody();

        // Second POST with same key
        byte[] secondResponse = web.post().uri("/communication/singleEmail")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(emailBody)
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals("Idempotency-Replayed", "true")
                .expectBody(byte[].class).returnResult().getResponseBody();

        // Responses should be byte-identical
        assertThat(firstResponse).isEqualTo(secondResponse);
    }

    @Test
    void exactlyOneIdempotencyDocPersistedOnDoublePost() {
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> emailBody = Map.of(
                "to", "recipient@example.com",
                "subject", "Subject", "body", "Body");

        web.post().uri("/communication/singleEmail")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(emailBody)
                .exchange().expectStatus().isOk();

        web.post().uri("/communication/singleEmail")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(emailBody)
                .exchange().expectStatus().isOk();

        List<?> docs = idempotencyKeys.findAll().collectList().block();
        assertThat(docs).hasSize(1);
    }

    @Test
    void sideEffectFiresExactlyOnce() {
        String idempotencyKey = UUID.randomUUID().toString();
        Map<String, Object> emailBody = Map.of(
                "to", "once@example.com",
                "subject", "Once", "body", "Body");

        web.post().uri("/communication/singleEmail")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(emailBody)
                .exchange().expectStatus().isOk();

        web.post().uri("/communication/singleEmail")
                .header("Authorization", "Bearer " + token)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(emailBody)
                .exchange().expectStatus().isOk();

        // EmailService.sendSingleEmail should have been called exactly once
        Mockito.verify(emailService, Mockito.times(1)).sendSingleEmail(any());
    }

    @Test
    void missingIdempotencyKeyOnAnnotatedRouteReturns400WithCode3100() {
        // AC-4: missing header on @IdempotentRoute endpoint -> 400/errorCode=3100
        Map<String, Object> emailBody = Map.of(
                "to", "recipient@example.com",
                "subject", "No Key", "body", "Body");

        web.post().uri("/communication/singleEmail")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(emailBody)
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody(Map.class).consumeWith(result -> {
                    Map<?, ?> body = result.getResponseBody();
                    assertThat(body).isNotNull();
                    assertThat(body.get("errorCode")).isEqualTo(3100);
                });
    }

    @Test
    void nonAnnotatedPostWithKeyIsNotStored() {
        // AC-4: a non-@IdempotentRoute POST with Idempotency-Key header should NOT
        // be stored in idempotency_keys
        String idempotencyKey = UUID.randomUUID().toString();

        // POST /auth/login is not @IdempotentRoute — should pass through
        web.post().uri("/auth/login")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", "idempotency@example.test", "password", "pass1234"))
                .exchange()
                .expectStatus().isOk();

        // Should not be stored
        List<?> docs = idempotencyKeys.findAll().collectList().block();
        assertThat(docs).isEmpty();
    }

    private String login(String email, String password) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", password))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
