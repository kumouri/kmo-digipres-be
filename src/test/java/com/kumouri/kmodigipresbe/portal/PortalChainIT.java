package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * Smoke-tests the portal {@link org.springframework.security.web.server.SecurityWebFilterChain}
 * boundary: portal-anonymous endpoints respond, portal-authenticated endpoints reject,
 * and the staff chain still requires staff JWTs for {@code /contacts}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PortalChainIT {

    @Autowired WebTestClient web;

    @Test
    void portalProvidersReachableAnonymously() {
        web.get().uri("/portal/auth/providers")
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void portalMeRejectsUnauthenticated() {
        web.get().uri("/portal/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void staffContactsStillRejectsUnauthenticated() {
        web.get().uri("/contacts")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
