package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.config.PortalProperties;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.portal.MagicLinkService;
import com.kumouri.kmodigipresbe.tenancy.HostTenantResolver;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Security fix BE-05 — the magic-link email base is derived server-side and a client
 * {@code linkBaseUrl} is ignored. We capture the actual emailed body and prove its link
 * points at the server-configured {@code portalProperties.successRedirect()} base, NOT at
 * an attacker-supplied URL.
 *
 * <p>Uses {@code @MockBean EmailService} to capture the outbound message (the only way to
 * observe the constructed link without live SMTP).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class MagicLinkLinkBaseUrlIT {

    private static final String ATTACKER_URL = "https://evil.example.com/steal";

    @Autowired WebTestClient web;
    @Autowired TenantRepository tenants;
    @Autowired MagicLinkService magicLinkService;
    @Autowired PortalProperties portalProperties;
    @Autowired org.springframework.data.mongodb.core.ReactiveMongoTemplate mongo;

    @MockBean EmailService emailService;

    private Tenant tenant;

    @BeforeEach
    void seed() {
        mongo.remove(new org.springframework.data.mongodb.core.query.Query(), Tenant.class).block();
        tenant = tenants.save(Tenant.builder()
                .id(UUID.randomUUID())
                .slug("magic-bb-" + UUID.randomUUID())
                .displayName("LinkBase Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .clientSignupPolicy(Tenant.ClientSignupPolicy.INVITE_ONLY)
                .build()).block();
        when(emailService.sendSingleEmail(any())).thenReturn(Mono.just(true));
    }

    // ─── HTTP contract: a body linkBaseUrl is ignored (field no longer bound) ─────

    @Test
    void httpRequest_withClientLinkBaseUrl_ignored_andLinkUsesServerConfig() {
        web.post().uri("/portal/auth/magic-link")
                .header(HostTenantResolver.SLUG_HEADER, tenant.getSlug())
                .bodyValue(Map.of(
                        "email", "victim@example.com",
                        // Attacker tries to steer the emailed link — must be IGNORED.
                        "linkBaseUrl", ATTACKER_URL))
                .exchange()
                .expectStatus().isOk();

        ArgumentCaptor<SingleEmailCommunicationRequest> captor =
                ArgumentCaptor.forClass(SingleEmailCommunicationRequest.class);
        verify(emailService, times(1)).sendSingleEmail(captor.capture());
        String body = captor.getValue().body();

        // The emailed link must NOT carry the attacker host…
        assertThat(body).doesNotContain(ATTACKER_URL);
        assertThat(body).doesNotContain("evil.example.com");
        // …and must use the server-configured success-redirect base + the magic_token param.
        assertThat(body).contains(portalProperties.successRedirect());
        assertThat(body).contains("magic_token=");
    }

    // ─── Service contract: request() no longer takes a linkBaseUrl at all ─────────

    @Test
    void serviceRequest_usesServerConfigBase() {
        magicLinkService.request(tenant, "direct@example.com", null)
                .contextWrite(TenantContextHolder.write(
                        new TenantContext(tenant.getId(), null, Set.of())))
                .block();

        ArgumentCaptor<SingleEmailCommunicationRequest> captor =
                ArgumentCaptor.forClass(SingleEmailCommunicationRequest.class);
        verify(emailService, times(1)).sendSingleEmail(captor.capture());
        String body = captor.getValue().body();
        assertThat(body).contains(portalProperties.successRedirect());
        assertThat(body).contains("magic_token=");
    }
}
