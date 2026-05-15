package com.kumouri.kmodigipresbe.servicehub;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.servicehub.KnowledgeBaseArticle;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.KnowledgeBaseArticleRepository;
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

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PublicKbIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired KnowledgeBaseArticleRepository articles;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String tenantSlug;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), KnowledgeBaseArticle.class).block();

        tenantId = UUID.randomUUID();
        tenantSlug = "public-kb-" + tenantId.toString().substring(0, 8);
        tenants.save(Tenant.builder()
                .id(tenantId).slug(tenantSlug)
                .displayName("Public KB").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("admin@pubkb.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Admin").roles(Set.of("ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();
    }

    @Test
    void publishedArticle_returns200AtPublicUrl() {
        articles.save(KnowledgeBaseArticle.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("How to prevent pests")
                .body("Prevention is better than cure.")
                .slug("prevent-pests")
                .publishedAt(Instant.now())
                .build()).block();

        web.get().uri("/public/kb/" + tenantSlug + "/prevent-pests")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.slug").isEqualTo("prevent-pests")
                .jsonPath("$.title").isEqualTo("How to prevent pests");
    }

    @Test
    void draftArticle_returns404AtPublicUrl() {
        articles.save(KnowledgeBaseArticle.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .title("Draft article")
                .body("Not published yet.")
                .slug("draft-article")
                .publishedAt(null)
                .build()).block();

        web.get().uri("/public/kb/" + tenantSlug + "/draft-article")
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void unknownSlug_returns404() {
        web.get().uri("/public/kb/" + tenantSlug + "/nonexistent")
                .exchange()
                .expectStatus().isNotFound();
    }
}
