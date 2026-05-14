package com.kumouri.kmodigipresbe.tenancy;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Optional;

/**
 * Resolves the tenant from the authenticated JWT and writes a {@link TenantContext}
 * into the Reactor context for downstream filters and handlers.
 *
 * <p>Ordering matters: this filter must run <em>after</em> Spring Security's
 * {@code WebFilterChainProxy} (registered at {@link SecurityProperties#DEFAULT_FILTER_ORDER}
 * = {@code HIGHEST_PRECEDENCE + 50}). Earlier than that and the security chain hasn't
 * yet propagated the {@code SecurityContext} into the Reactor context, so
 * {@link ReactiveSecurityContextHolder#getContext()} returns empty and tenant
 * resolution silently no-ops — every authenticated request would then hit
 * {@code TenantContextHolder.required()} downstream and fail with a 403.
 */
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 1)
@RequiredArgsConstructor
public class TenantWebFilter implements WebFilter {

    private final TenantResolver tenantResolver;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        // Carry the resolved tenant as Optional through the pipeline so we can dispatch
        // on its presence once. Naively chaining .switchIfEmpty(chain.filter(exchange))
        // here is wrong: chain.filter() returns Mono<Void>, which always completes
        // "empty" (onComplete with no onNext), so .switchIfEmpty would fire *after* the
        // first chain.filter() succeeds — running the chain twice and crashing the second
        // pass on the already-committed response.
        return ReactiveSecurityContextHolder.getContext()
                .flatMap(secCtx -> tenantResolver.resolve(secCtx.getAuthentication()))
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(maybeTenant -> maybeTenant
                        .map(tenant -> chain.filter(exchange)
                                .contextWrite(TenantContextHolder.write(tenant)))
                        .orElseGet(() -> chain.filter(exchange)));
    }
}
