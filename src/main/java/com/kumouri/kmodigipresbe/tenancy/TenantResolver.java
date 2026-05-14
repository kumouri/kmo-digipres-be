package com.kumouri.kmodigipresbe.tenancy;

import org.springframework.security.core.Authentication;
import reactor.core.publisher.Mono;

public interface TenantResolver {
    Mono<TenantContext> resolve(Authentication authentication);
}
