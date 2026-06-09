package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import com.kumouri.kmodigipresbe.service.JwtTokenService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T3 (Real Estate "Midnight Responder") — the {@link MidnightResponderConfigController} CRUD + validation +
 * gate. PUT upsert (idempotent), GET read (4380 when absent), 4381 on a tier campaign id that is not a
 * tenant campaign, and the STAFF role gate. {@link TwilioSmsService} mocked so the responder beans
 * construct. No live external (§7).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false",
        "kmosf.modules.realestate.enabled=true"
})
class MidnightResponderConfigIT {

    @Autowired WebTestClient web;
    @Autowired ReactiveMongoTemplate mongo;
    @Autowired JwtTokenService jwt;
    @Autowired UserRepository users;

    @MockitoBean TwilioSmsService twilioSmsService;

    private UUID tenantId;
    private String staffToken;
    private UUID warmCampaignId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), User.class).block();
        mongo.remove(new Query(), NurtureCampaign.class).block();
        mongo.remove(new Query(), MidnightResponderConfig.class).block();

        tenantId = UUID.randomUUID();
        mongo.save(Tenant.builder().id(tenantId).slug("midnight-config-" + tenantId)
                .displayName("Midnight Config IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("realestate", "responder", "nurture"))
                .aiBudgetUsd(new BigDecimal("5.00"))
                .build()).block();
        User staff = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("staff@midnight-cfg.test")
                .roles(Set.of("STAFF")).status(User.UserStatus.ACTIVE).build();
        users.save(staff).block();
        staffToken = "Bearer " + jwt.mint(staff);

        warmCampaignId = UUID.randomUUID();
        mongo.save(NurtureCampaign.builder().id(warmCampaignId).tenantId(tenantId)
                .name("Warm " + warmCampaignId).active(true)
                .segments(List.of(new NurtureSegmentDefinition(DormancyBucket.B, 0, null, null, null)))
                .steps(List.of())
                .build()).block();
    }

    @Test
    void get_returns4380_whenNoConfig() {
        web.get().uri("/api/v1/realestate/responder/config")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void upsert_thenGet_roundTrips() {
        // First upsert — creates.
        web.put().uri("/api/v1/realestate/responder/config")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MidnightResponderConfigDTO(warmCampaignId, null, true, 9, 17))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.warmCampaignId").isEqualTo(warmCampaignId.toString())
                .jsonPath("$.delegateHandoffToResponder").isEqualTo(true)
                .jsonPath("$.afterHoursStartHour").isEqualTo(9)
                .jsonPath("$.afterHoursEndHour").isEqualTo(17);

        // Exactly one row persisted.
        assertThat(mongo.findAll(MidnightResponderConfig.class).collectList().block()).hasSize(1);

        // GET returns it.
        web.get().uri("/api/v1/realestate/responder/config")
                .header("Authorization", staffToken)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.warmCampaignId").isEqualTo(warmCampaignId.toString());

        // Second upsert — updates in place (still one row).
        web.put().uri("/api/v1/realestate/responder/config")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MidnightResponderConfigDTO(null, null, false, null, null))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.delegateHandoffToResponder").isEqualTo(false)
                // warmCampaignId preserved (null in the patch keeps the prior value).
                .jsonPath("$.warmCampaignId").isEqualTo(warmCampaignId.toString());
        assertThat(mongo.findAll(MidnightResponderConfig.class).collectList().block()).hasSize(1);
    }

    @Test
    void upsert_returns4381_whenTierCampaignNotATenantCampaign() {
        web.put().uri("/api/v1/realestate/responder/config")
                .header("Authorization", staffToken)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(new MidnightResponderConfigDTO(UUID.randomUUID(), null, true, null, null))
                .exchange()
                .expectStatus().isBadRequest();
        // Nothing persisted on a validation failure.
        assertThat(mongo.findAll(MidnightResponderConfig.class).collectList().block()).isEmpty();
    }

    @Test
    void get_returns403_forNonStaff() {
        User noRole = User.builder().id(UUID.randomUUID()).tenantId(tenantId).email("norole@midnight-cfg.test")
                .roles(Set.of()).status(User.UserStatus.ACTIVE).build();
        users.save(noRole).block();
        String noRoleToken = "Bearer " + jwt.mint(noRole);

        web.get().uri("/api/v1/realestate/responder/config")
                .header("Authorization", noRoleToken)
                .exchange()
                .expectStatus().isForbidden();
    }
}
