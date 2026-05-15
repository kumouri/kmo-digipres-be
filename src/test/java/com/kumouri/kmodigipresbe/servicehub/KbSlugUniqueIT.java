package com.kumouri.kmodigipresbe.servicehub;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.servicehub.KnowledgeBaseArticle;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class KbSlugUniqueIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private String adminToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), KnowledgeBaseArticle.class).block();

        UUID tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("kb-slug-" + tenantId)
                .displayName("KB Slug").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@kb-slug.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Admin").roles(Set.of("ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        adminToken = login("admin@kb-slug.test");
    }

    @Test
    void duplicateSlug_withinSameTenant_returns409WithCode2921() {
        Map<Object, Object> article = Map.of(
                "title", "First Article",
                "body", "Some body text.",
                "slug", "duplicate-slug");

        // Create first — should succeed
        web.post().uri("/knowledge-base/articles")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(article)
                .exchange().expectStatus().isCreated();

        // Create second with same slug — should fail
        web.post().uri("/knowledge-base/articles")
                .header("Authorization", "Bearer " + adminToken)
                .bodyValue(Map.of(
                        "title", "Second Article",
                        "body", "Different body.",
                        "slug", "duplicate-slug"))
                .exchange()
                .expectStatus().is4xxClientError()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2921);
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
