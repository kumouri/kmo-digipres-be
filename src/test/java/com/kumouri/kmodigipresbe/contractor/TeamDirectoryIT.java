package com.kumouri.kmodigipresbe.contractor;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.response.TeamMemberView;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase J — team / contractor directory: ADMIN-only create / list, contractor role implies
 * STAFF, INVITED-by-default (no password), passwordHash never leaks, duplicate email → 4004.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {"kmosf.quartz.proof-job.enabled=false"})
class TeamDirectoryIT {

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired UserRepository users;
    @Autowired JwtTokenService jwt;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private String adminToken;
    private String staffToken;

    @BeforeEach
    void seed() {
        clean();
        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("tm-it-" + tenantId)
                .displayName("TM IT").status(Tenant.TenantStatus.ACTIVE).build()).block();

        User admin = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("admin@tm.test")
                .roles(Set.of("STAFF", "ADMIN")).status(User.UserStatus.ACTIVE).build();
        users.save(admin).block();
        adminToken = "Bearer " + jwt.mint(admin);

        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@tm.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);
    }

    @AfterEach
    void cleanup() {
        clean();
    }

    private void clean() {
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    @Test
    void adminCreatesContractorInvitedWithRatesAndNoPasswordLeak() {
        web.post().uri("/team")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"jordan@tm.test","displayName":"Jordan Rivera","roles":["CONTRACTOR"],
                         "defaultBillRate":150,"defaultCostRate":75}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("INVITED")
                .jsonPath("$.portal").isEqualTo("STAFF")
                .jsonPath("$.passwordHash").doesNotExist();

        User saved = users.findByTenantIdAndEmail(tenantId, "jordan@tm.test").block();
        assertThat(saved).isNotNull();
        assertThat(saved.getRoles()).contains("STAFF", "CONTRACTOR"); // CONTRACTOR implies STAFF
        assertThat(saved.getPasswordHash()).isNull();
        assertThat(saved.getDefaultBillRate()).isEqualByComparingTo("150");
        assertThat(saved.getDefaultCostRate()).isEqualByComparingTo("75");
    }

    @Test
    void createWithPasswordIsActive() {
        web.post().uri("/team")
                .header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""
                        {"email":"active@tm.test","displayName":"Active One","roles":["STAFF"],"password":"hunter2hunter2"}
                        """)
                .exchange()
                .expectStatus().isCreated()
                .expectBody()
                .jsonPath("$.status").isEqualTo("ACTIVE");

        User saved = users.findByTenantIdAndEmail(tenantId, "active@tm.test").block();
        assertThat(saved.getPasswordHash()).isNotNull();
    }

    @Test
    void listReturnsStaffAndContractorsOnly() {
        web.get().uri("/team")
                .header("Authorization", adminToken)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(TeamMemberView.class)
                .value(list -> assertThat(list).hasSize(2)); // seeded admin + staff (both portal=STAFF)
    }

    @Test
    void nonAdminCannotListTeam() {
        web.get().uri("/team")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.errorCode").isEqualTo(1800);
    }

    @Test
    void duplicateEmailReturns409With4004() {
        String body = """
                {"email":"dupe@tm.test","displayName":"Dupe","roles":["CONTRACTOR"]}
                """;
        web.post().uri("/team").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated();

        web.post().uri("/team").header("Authorization", adminToken)
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.errorCode").isEqualTo(4004);
    }
}
