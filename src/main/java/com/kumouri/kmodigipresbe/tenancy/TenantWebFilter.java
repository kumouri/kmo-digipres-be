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
        return ReactiveSecurityContextHolder.getContext()
                .map(secCtx -> secCtx.getAuthentication())
                .flatMap(tenantResolver::resolve)
                .flatMap(tenant -> chain.filter(exchange)
                        .contextWrite(TenantContextHolder.write(tenant)))
                .switchIfEmpty(chain.filter(exchange));
    }
}
