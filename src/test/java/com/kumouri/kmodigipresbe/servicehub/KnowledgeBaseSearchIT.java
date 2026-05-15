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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class KnowledgeBaseSearchIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired KnowledgeBaseArticleRepository articles;
    @Autowired PasswordEncoder encoder;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String staffToken;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), KnowledgeBaseArticle.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(tenantId).slug("kb-search-" + tenantId)
                .displayName("KB Search").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();
        users.save(User.builder()
                .id(UUID.randomUUID()).tenantId(tenantId)
                .email("staff@kb.test")
                .passwordHash(encoder.encode("hunter2hunter2"))
                .displayName("Staff").roles(Set.of("ADMIN"))
                .status(User.UserStatus.ACTIVE).build()).block();

        staffToken = login("staff@kb.test");
    }

    @Test
    void publishedArticleAppearsInSearch() {
        // Create a published article via API
        Map<?, ?> created = web.post().uri("/knowledge-base/articles")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of(
                        "title", "Pest Control Guide",
                        "body", "How to handle pest control situations effectively.",
                        "slug", "pest-control-guide",
                        "tags", List.of("pest", "control")))
                .exchange().expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();

        String articleId = (String) created.get("id");

        // Publish it
        web.post().uri("/knowledge-base/articles/" + articleId + "/publish")
                .header("Authorization", "Bearer " + staffToken)
                .exchange().expectStatus().isOk();

        // Search for it
        List<?> results = web.post().uri("/knowledge-base/search")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("query", "pest control"))
                .exchange().expectStatus().isOk()
                .expectBody(List.class).returnResult().getResponseBody();

        assertThat(results).isNotEmpty();
    }

    @Test
    void unpublishedArticleExcludedFromSearch() {
        // Draft article only — no publish call
        web.post().uri("/knowledge-base/articles")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of(
                        "title", "Draft article about rodents",
                        "body", "Rodent control hidden draft.",
                        "slug", "draft-rodent-article"))
                .exchange().expectStatus().isCreated();

        List<?> results = web.post().uri("/knowledge-base/search")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("query", "rodent"))
                .exchange().expectStatus().isOk()
                .expectBody(List.class).returnResult().getResponseBody();

        assertThat(results).isEmpty();
    }

    @Test
    void crossTenantArticleExcludedFromSearch() {
        UUID otherTenantId = UUID.randomUUID();
        tenants.save(Tenant.builder()
                .id(otherTenantId).slug("kb-other-" + otherTenantId)
                .displayName("Other Tenant").status(Tenant.TenantStatus.ACTIVE)
                .build()).block();

        // Create published article directly for the OTHER tenant
        articles.save(KnowledgeBaseArticle.builder()
                .id(UUID.randomUUID()).tenantId(otherTenantId)
                .title("Termites guide")
                .body("How to handle termites in the other tenant's properties.")
                .slug("termites-other")
                .publishedAt(Instant.now())
                .build()).block();

        List<?> results = web.post().uri("/knowledge-base/search")
                .header("Authorization", "Bearer " + staffToken)
                .bodyValue(Map.of("query", "termites"))
                .exchange().expectStatus().isOk()
                .expectBody(List.class).returnResult().getResponseBody();

        assertThat(results).isEmpty();
    }

    private String login(String email) {
        Map<?, ?> body = web.post().uri("/auth/login")
                .bodyValue(Map.of("email", email, "password", "hunter2hunter2"))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("token");
    }
}
