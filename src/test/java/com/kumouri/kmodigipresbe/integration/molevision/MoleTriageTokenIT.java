package com.kumouri.kmodigipresbe.integration.molevision;

import com.kumouri.kmodigipresbe.controller.integration.MoleTriageTokenController;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pure unit test (no Docker / no Spring context) for {@link MoleTriageTokenController}. Combines two
 * shipped patterns: {@code MoleTripwireTokenServiceTest}'s self-contained posture (a fixed
 * {@link Clock} + secret via a mocked {@link ObjectProvider}, so a {@link PublicWidgetTokenService}
 * is constructed without a context) and the {@code EquipmentDeleteRoleGuardIT} RoleGuard pattern
 * (call the controller method directly with a synthetic {@link TenantContext} written into the
 * Reactor context — {@code RoleGuard} reads from there, so this exercises the same path a
 * JWT-resolved context would, minus the JWT login + Mongo boot).
 *
 * <ul>
 *   <li>ADMIN → 201 (the {@code @ResponseStatus(CREATED)} declaration) + a verifiable, tenant-scoped
 *       365-day {@code mole-triage} token;</li>
 *   <li>non-ADMIN → {@link DigiPresBeException} {@code errorCode=1800, status=403}, no token issued.</li>
 * </ul>
 */
class MoleTriageTokenIT {

    private static final String SECRET = "mole-triage-token-unit-secret-0123456789";
    private static final Instant NOW = Instant.parse("2026-06-03T00:00:00Z");

    @SuppressWarnings("unchecked")
    private PublicWidgetTokenService tokenService() {
        ObjectProvider<Clock> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
        return new PublicWidgetTokenService(SECRET, provider);
    }

    @Test
    void admin_mintsTenantScoped365DayToken() {
        PublicWidgetTokenService tokens = tokenService();
        UUID tenantId = UUID.randomUUID();
        TenantContext admin = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF", "ADMIN"));

        Map<String, String> body = new MoleTriageTokenController(tokens).issue()
                .contextWrite(TenantContextHolder.write(admin))
                .block();

        assertThat(body).isNotNull().containsKey("token");
        PublicWidgetToken claims = tokens.verify(body.get("token"));
        assertThat(claims.tenantId()).isEqualTo(tenantId);
        assertThat(claims.widgetType()).isEqualTo(MoleTriageService.WIDGET_TYPE);
        assertThat(claims.expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(365)));
    }

    @Test
    void nonAdmin_isRejected_1800_403() {
        PublicWidgetTokenService tokens = tokenService();
        TenantContext staff = new TenantContext(UUID.randomUUID(), UUID.randomUUID(), Set.of("STAFF"));

        Throwable err = new MoleTriageTokenController(tokens).issue()
                .contextWrite(TenantContextHolder.write(staff))
                .map(v -> (Throwable) null)
                .onErrorResume(Mono::just)
                .block();

        assertThat(err).isInstanceOf(DigiPresBeException.class);
        DigiPresBeException dpb = (DigiPresBeException) err;
        assertThat(dpb.getErrorCode()).isEqualTo(1800);
        assertThat(dpb.getHttpStatusCode()).isEqualTo(403);
    }

    @Test
    void issueMethod_declares201Created() throws Exception {
        ResponseStatus rs = MoleTriageTokenController.class
                .getMethod("issue").getAnnotation(ResponseStatus.class);
        assertThat(rs).isNotNull();
        assertThat(rs.value()).isEqualTo(HttpStatus.CREATED);
    }
}
