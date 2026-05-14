package com.kumouri.kmodigipresbe.service.widget;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PublicWidgetTokenServiceTest {

    @Test
    void roundtrip_validToken_returnsClaims() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-14T00:00:00Z"), ZoneOffset.UTC);
        PublicWidgetTokenService svc = service("my-test-secret-32-bytes-aa-bb-cc-dd", fixed);

        UUID tenant = UUID.randomUUID();
        String token = svc.issue(tenant, "booking", Duration.ofHours(1));

        PublicWidgetToken claims = svc.verify(token);
        assertThat(claims.tenantId()).isEqualTo(tenant);
        assertThat(claims.widgetType()).isEqualTo("booking");
        assertThat(claims.expiresAt()).isEqualTo(Instant.parse("2026-05-14T01:00:00Z"));
    }

    @Test
    void verify_expiredToken_rejected_errorCode1603() {
        Clock advancing = mock(Clock.class);
        when(advancing.instant())
                .thenReturn(Instant.parse("2026-05-14T00:00:00Z"))    // issue
                .thenReturn(Instant.parse("2026-05-14T02:00:00Z"));   // verify (after expiry)

        PublicWidgetTokenService svc = service("my-test-secret", advancing);
        String token = svc.issue(UUID.randomUUID(), "form", Duration.ofHours(1));

        assertThatThrownBy(() -> svc.verify(token))
                .isInstanceOfSatisfying(DigiPresBeException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(1603);
                    assertThat(e.getHttpStatusCode()).isEqualTo(401);
                });
    }

    @Test
    void verify_tamperedPayload_rejected_errorCode1602() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-14T00:00:00Z"), ZoneOffset.UTC);
        PublicWidgetTokenService svc = service("my-test-secret", fixed);
        String token = svc.issue(UUID.randomUUID(), "form", Duration.ofHours(1));

        // Flip the first base64url character of the payload — signature won't verify.
        char tamperedFirst = token.charAt(0) == 'A' ? 'B' : 'A';
        String tampered = tamperedFirst + token.substring(1);

        assertThatThrownBy(() -> svc.verify(tampered))
                .isInstanceOfSatisfying(DigiPresBeException.class, e ->
                        assertThat(e.getErrorCode()).isIn(1601, 1602));
    }

    @Test
    void verify_tokenSignedBySomeoneElse_rejected_errorCode1602() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-14T00:00:00Z"), ZoneOffset.UTC);
        PublicWidgetTokenService alice = service("alice-secret-32-bytes-1234567890aa", fixed);
        PublicWidgetTokenService bob = service("bob-secret-32-bytes-1234567890aabb", fixed);

        String fromAlice = alice.issue(UUID.randomUUID(), "form", Duration.ofHours(1));

        // Bob's service can't verify Alice's token.
        assertThatThrownBy(() -> bob.verify(fromAlice))
                .isInstanceOfSatisfying(DigiPresBeException.class, e ->
                        assertThat(e.getErrorCode()).isEqualTo(1602));
        // But Alice's own service can.
        assertThat(alice.verify(fromAlice)).isNotNull();
    }

    @Test
    void verify_emptyOrMalformedToken_rejected() {
        Clock fixed = Clock.fixed(Instant.parse("2026-05-14T00:00:00Z"), ZoneOffset.UTC);
        PublicWidgetTokenService svc = service("my-test-secret", fixed);

        Stream.of("", "  ", "no-dot-here", "a.", ".b", "!@#$.%^&*")
                .forEach(bad -> assertThatThrownBy(() -> svc.verify(bad))
                        .isInstanceOf(DigiPresBeException.class));
    }

    @Test
    void issue_rejectsNullTenantOrBlankType() {
        PublicWidgetTokenService svc = service("my-test-secret",
                Clock.fixed(Instant.parse("2026-05-14T00:00:00Z"), ZoneOffset.UTC));
        assertThatThrownBy(() -> svc.issue(null, "form", Duration.ofHours(1)))
                .isInstanceOf(DigiPresBeException.class);
        assertThatThrownBy(() -> svc.issue(UUID.randomUUID(), "  ", Duration.ofHours(1)))
                .isInstanceOf(DigiPresBeException.class);
    }

    @SuppressWarnings("unchecked")
    private static PublicWidgetTokenService service(String secret, Clock clock) {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(org.mockito.ArgumentMatchers.any()))
                .thenReturn(clock);
        return new PublicWidgetTokenService(secret, provider);
    }
}
