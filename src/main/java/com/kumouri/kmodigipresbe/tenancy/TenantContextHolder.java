package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

import java.util.Optional;
import java.util.function.Function;

public final class TenantContextHolder {

    private TenantContextHolder() {
    }

    public static Mono<TenantContext> current() {
        return Mono.deferContextual(ctx ->
                ctx.<TenantContext>getOrEmpty(TenantContext.CONTEXT_KEY)
                        .map(Mono::just)
                        .orElse(Mono.empty()));
    }

    public static Mono<TenantContext> required() {
        return current().switchIfEmpty(Mono.error(() ->
                new DigiPresBeException("No tenant context for this request", 1001, 403)));
    }

    public static Optional<TenantContext> from(Context ctx) {
        return ctx.getOrEmpty(TenantContext.CONTEXT_KEY);
    }

    public static Function<Context, Context> write(TenantContext tenant) {
        return ctx -> ctx.put(TenantContext.CONTEXT_KEY, tenant);
    }
}
