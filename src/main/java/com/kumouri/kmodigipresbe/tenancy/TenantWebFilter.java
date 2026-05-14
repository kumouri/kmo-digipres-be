package com.kumouri.kmodigipresbe.tenancy;

import lombok.RequiredArgsConstructor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
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
