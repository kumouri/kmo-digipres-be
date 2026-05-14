package com.kumouri.kmodigipresbe.controller.widget;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Asserts the Phase 9b public-widget chain is wired correctly:
 * <ol>
 *   <li>Anonymous {@code POST /public/widget/sample/<valid-token>} returns 200 with
 *       the parsed claims — the chain bypasses authentication.</li>
 *   <li>An expired or tampered token returns 401 from the controller via
 *       {@link PublicWidgetTokenService} → {@code GlobalErrorHandler}.</li>
 *   <li>The staff route {@code GET /contacts} still returns 401 unauthenticated —
 *       proving the widget chain only claims {@code /public/widget/**} and the
 *       broader staff chain is undisturbed.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
class PublicWidgetChainIT {

    @Autowired WebTestClient web;
    @Autowired PublicWidgetTokenService tokens;

    @Test
    @SuppressWarnings("unchecked")
    void anonymousPostWithValidTokenReturns200AndClaims() {
        UUID tenant = UUID.randomUUID();
        String token = tokens.issue(tenant, "sample", Duration.ofHours(1));

        Map<String, Object> body = (Map<String, Object>) web.post()
                .uri("/public/widget/sample/" + token)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(body).isNotNull();
        assertThat(body.get("tenantId")).isEqualTo(tenant.toString());
        assertThat(body.get("widgetType")).isEqualTo("sample");
    }

    @Test
    void anonymousPostWithMalformedTokenReturns401() {
        web.post().uri("/public/widget/sample/not-a-real-token")
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").value(o -> assertThat((Integer) o).isBetween(1600, 1699));
    }

    @Test
    void staffRouteStillRejectsUnauthenticated() {
        // The widget chain matches only /public/widget/**; /contacts is owned by the
        // staff chain and must keep returning 401 for unauthenticated requests.
        web.get().uri("/contacts")
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
