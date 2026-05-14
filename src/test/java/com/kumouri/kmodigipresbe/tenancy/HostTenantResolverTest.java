package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Exercises the three host-tenant resolution mechanisms. The behavior is the only
 * thing keeping pre-auth portal endpoints scoped to the right tenant — they have no
 * JWT yet to provide tenant context.
 */
class HostTenantResolverTest {

    private final TenantRepository tenants = mock(TenantRepository.class);
    private final HostTenantResolver resolver = new HostTenantResolver(tenants);

    @Test
    void resolvesFromSubdomain() {
        Tenant t = Tenant.builder().id(UUID.randomUUID()).slug("acme").build();
        when(tenants.findBySlug("acme")).thenReturn(Mono.just(t));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/portal/auth/providers")
                        .header(HttpHeaders.HOST, "acme.crm.kmosf.dev"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext(t)
                .verifyComplete();
    }

    @Test
    void headerOverridesSubdomain() {
        Tenant t = Tenant.builder().id(UUID.randomUUID()).slug("override").build();
        when(tenants.findBySlug("override")).thenReturn(Mono.just(t));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/portal/auth/providers")
                        .header(HttpHeaders.HOST, "acme.crm.kmosf.dev")
                        .header(HostTenantResolver.SLUG_HEADER, "override"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext(t)
                .verifyComplete();
    }

    @Test
    void queryParamFallbackWhenNoSubdomain() {
        Tenant t = Tenant.builder().id(UUID.randomUUID()).slug("acme").build();
        when(tenants.findBySlug("acme")).thenReturn(Mono.just(t));

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/portal/auth/providers?tenant=acme")
                        .header(HttpHeaders.HOST, "localhost:8080"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectNext(t)
                .verifyComplete();
    }

    @Test
    void reservedSubdomainsDoNotResolve() {
        when(tenants.findBySlug(any())).thenReturn(Mono.empty());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/portal/auth/providers")
                        .header(HttpHeaders.HOST, "api.crm.kmosf.dev"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getErrorCode() == 1200)
                .verify();
    }

    @Test
    void unknownTenantSurfaces404() {
        when(tenants.findBySlug("nope")).thenReturn(Mono.empty());

        var exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/portal/auth/providers")
                        .header(HttpHeaders.HOST, "nope.crm.kmosf.dev"));

        StepVerifier.create(resolver.resolve(exchange))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getHttpStatusCode() == 404)
                .verify();
    }
}
