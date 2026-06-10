package com.kumouri.kmodigipresbe.security;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookDeliveryService;
import com.kumouri.kmodigipresbe.automation.webhook.WebhookSubscription;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import reactor.test.StepVerifier;

import java.util.UUID;

/**
 * Security fix BE-08 — the outbound webhook delivery path must reject an internal target.
 * This IT pins the guard back to the PRODUCTION-strict policy (overriding the relaxed
 * test-profile defaults) so a metadata / loopback / private delivery URL is blocked with
 * the SSRF error code {@link OutboundUrlGuard#ERROR_CODE} before any request is issued.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.outbound-guard.require-https=true",
        "kmosf.outbound-guard.allow-private-networks=false"
})
class WebhookDeliverySsrfIT {

    @Autowired WebhookDeliveryService delivery;

    private WebhookSubscription subTo(String url) {
        return WebhookSubscription.builder()
                .id(UUID.randomUUID()).tenantId(UUID.randomUUID())
                .name("ssrf-test").url(url).secret("s3cret").active(true)
                .build();
    }

    @Test
    void blocksMetadataEndpoint() {
        StepVerifier.create(delivery.testDeliver(subTo("http://169.254.169.254/latest/meta-data/")))
                .expectErrorSatisfies(err ->
                        org.assertj.core.api.Assertions.assertThat(((DigiPresBeException) err).getErrorCode())
                                .isEqualTo(OutboundUrlGuard.ERROR_CODE))
                .verify();
    }

    @Test
    void blocksLoopback() {
        StepVerifier.create(delivery.testDeliver(subTo("http://127.0.0.1:8080/internal")))
                .expectError(DigiPresBeException.class)
                .verify();
    }

    @Test
    void blocksPrivateRange() {
        StepVerifier.create(delivery.testDeliver(subTo("https://10.1.2.3/x")))
                .expectError(DigiPresBeException.class)
                .verify();
    }
}
