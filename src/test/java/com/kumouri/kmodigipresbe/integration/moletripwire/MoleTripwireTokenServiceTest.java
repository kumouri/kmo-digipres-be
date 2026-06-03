package com.kumouri.kmodigipresbe.integration.moletripwire;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 3 — pure unit test (no Docker) for {@link MoleTripwireTokenService}. Mirrors the Phase-1
 * {@code TwilioRequestValidatorTest} posture: self-contained, deterministic, fast — proves the
 * round-trip carries the Project id and that the constant-time HMAC verify rejects tampering /
 * expiry / a foreign secret. The {@code 4013} widgetType-mismatch branch lives in
 * {@code MoleTripwireService} (covered by {@code MoleTripwireIT}); this test proves the token layer
 * underneath it.
 */
class MoleTripwireTokenServiceTest {

    private static final String SECRET = "phase3-tripwire-unit-secret-0123456789";

    @SuppressWarnings("unchecked")
    private MoleTripwireTokenService service(String secret, Clock clock) {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any())).thenReturn(clock);
        return new MoleTripwireTokenService(secret, provider);
    }

    @Test
    void issueThenVerify_roundTripsTenantAndProject() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-02T00:00:00Z"), ZoneOffset.UTC);
        MoleTripwireTokenService svc = service(SECRET, clock);
        UUID tenant = UUID.randomUUID();
        UUID project = UUID.randomUUID();

        String token = svc.issue(tenant, project, Duration.ofDays(180));
        MoleTripwireToken claims = svc.verify(token);

        assertThat(claims.tenantId()).isEqualTo(tenant);
        assertThat(claims.projectId()).isEqualTo(project);
        assertThat(claims.widgetType()).isEqualTo(MoleTripwireTokenService.WIDGET_TYPE);
        assertThat(claims.expiresAt()).isEqualTo(Instant.parse("2026-06-02T00:00:00Z")
                .plus(Duration.ofDays(180)));
    }

    @Test
    void tamperedSignature_isRejected_1602() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-02T00:00:00Z"), ZoneOffset.UTC);
        MoleTripwireTokenService svc = service(SECRET, clock);
        String token = svc.issue(UUID.randomUUID(), UUID.randomUUID(), Duration.ofHours(1));
        String tampered = token.substring(0, token.length() - 4) + "AAAA";

        assertThatThrownBy(() -> svc.verify(tampered))
                .isInstanceOf(DigiPresBeException.class)
                .extracting(e -> ((DigiPresBeException) e).getErrorCode())
                .isEqualTo(1602);
    }

    @Test
    void foreignSecret_signatureRejected_1602() {
        Clock clock = Clock.fixed(Instant.parse("2026-06-02T00:00:00Z"), ZoneOffset.UTC);
        String token = service(SECRET, clock).issue(UUID.randomUUID(), UUID.randomUUID(),
                Duration.ofHours(1));
        // A service with a DIFFERENT secret must reject the signature.
        MoleTripwireTokenService other = service("a-completely-different-secret-9876543210", clock);

        assertThatThrownBy(() -> other.verify(token))
                .isInstanceOf(DigiPresBeException.class)
                .extracting(e -> ((DigiPresBeException) e).getErrorCode())
                .isEqualTo(1602);
    }

    @Test
    void expiredToken_isRejected_1603() {
        Clock issueClock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        String token = service(SECRET, issueClock).issue(UUID.randomUUID(), UUID.randomUUID(),
                Duration.ofHours(1));
        // Verify a day later with the same secret — expired.
        Clock laterClock = Clock.fixed(Instant.parse("2026-01-02T00:00:00Z"), ZoneOffset.UTC);
        MoleTripwireTokenService later = service(SECRET, laterClock);

        assertThatThrownBy(() -> later.verify(token))
                .isInstanceOf(DigiPresBeException.class)
                .extracting(e -> ((DigiPresBeException) e).getErrorCode())
                .isEqualTo(1603);
    }

    @Test
    void malformedToken_isRejected_160x() {
        MoleTripwireTokenService svc = service(SECRET,
                Clock.fixed(Instant.parse("2026-06-02T00:00:00Z"), ZoneOffset.UTC));

        assertThatThrownBy(() -> svc.verify("no-dot-here"))
                .isInstanceOf(DigiPresBeException.class)
                .extracting(e -> ((DigiPresBeException) e).getErrorCode())
                .isEqualTo(1601);
        assertThatThrownBy(() -> svc.verify(""))
                .isInstanceOf(DigiPresBeException.class)
                .extracting(e -> ((DigiPresBeException) e).getErrorCode())
                .isEqualTo(1600);
    }
}
