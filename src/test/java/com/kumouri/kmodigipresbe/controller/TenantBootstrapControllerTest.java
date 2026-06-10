package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.request.BootstrapTenantRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.service.TenantBootstrapService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Security fix BE-18 — the bootstrap-token comparison is now constant-time
 * ({@code MessageDigest.isEqual}). Behavior must be unchanged: the exact token works, any
 * other / null / blank token is rejected 401/1502, and an unconfigured server fails closed
 * 503/1501.
 */
class TenantBootstrapControllerTest {

    private TenantBootstrapService service;
    private TenantBootstrapController controller;

    private static final BootstrapTenantRequest BODY = new BootstrapTenantRequest(
            "acme", "Acme", "admin@acme.test", "pw-strong-enough", "Admin");

    @BeforeEach
    void setUp() {
        service = mock(TenantBootstrapService.class);
        when(service.bootstrap(any())).thenReturn(Mono.just(
                Tenant.builder().id(UUID.randomUUID()).slug("acme").build()));
        controller = new TenantBootstrapController(service);
    }

    @Test
    void correctTokenIsAccepted() {
        ReflectionTestUtils.setField(controller, "bootstrapToken", "s3cret-token");

        StepVerifier.create(controller.create("s3cret-token", BODY))
                .expectNextCount(1)
                .verifyComplete();
    }

    @Test
    void wrongTokenIsRejected401() {
        ReflectionTestUtils.setField(controller, "bootstrapToken", "s3cret-token");

        StepVerifier.create(controller.create("not-the-token", BODY))
                .expectErrorSatisfies(err -> {
                    assertThat(err).isInstanceOf(DigiPresBeException.class);
                    DigiPresBeException dpb = (DigiPresBeException) err;
                    assertThat(dpb.getHttpStatusCode()).isEqualTo(401);
                    assertThat(dpb.getErrorCode()).isEqualTo(1502);
                })
                .verify();
    }

    @Test
    void nullTokenIsRejected401_failsClosed() {
        ReflectionTestUtils.setField(controller, "bootstrapToken", "s3cret-token");

        StepVerifier.create(controller.create(null, BODY))
                .expectErrorSatisfies(err -> assertThat(((DigiPresBeException) err).getHttpStatusCode())
                        .isEqualTo(401))
                .verify();
    }

    @Test
    void unconfiguredServerFailsClosed503() {
        ReflectionTestUtils.setField(controller, "bootstrapToken", "");

        StepVerifier.create(controller.create("anything", BODY))
                .expectErrorSatisfies(err -> {
                    DigiPresBeException dpb = (DigiPresBeException) err;
                    assertThat(dpb.getHttpStatusCode()).isEqualTo(503);
                    assertThat(dpb.getErrorCode()).isEqualTo(1501);
                })
                .verify();
    }
}
