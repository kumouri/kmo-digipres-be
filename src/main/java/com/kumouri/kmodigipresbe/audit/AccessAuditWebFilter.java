package com.kumouri.kmodigipresbe.audit;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * HIPAA "audit controls" (45 CFR 164.312(b)) read-side access logging: records one
 * append-only {@link AccessAuditEvent} per completed, authenticated request, so the
 * system can answer <em>who read which record, when</em> (the write-side
 * {@link AuditEvent} already covers CREATE / UPDATE / DELETE).
 *
 * <h2>Ordering</h2>
 * Runs at {@link SecurityProperties#DEFAULT_FILTER_ORDER} {@code + 3} — after
 * {@code TenantWebFilter} ({@code +1}, which writes the {@code TenantContext} this relies
 * on) and {@code IdempotencyWebFilter} ({@code +2}). The event is written in a
 * {@code .then(...)} after the chain completes so the final response status is known.
 * Requests that fail with an unhandled exception (rendered as 500 by the global error
 * handler) are not captured in this first cut; normally-completed requests — including
 * auth failures rendered as 401/403 responses — are.
 *
 * <h2>Opt-in</h2>
 * OFF by default. Enable per deployment with
 * {@code kmosf.audit.access-tracking.enabled=true}. The
 * {@link ConditionalOnWebApplication}{@code (REACTIVE)} guard mirrors
 * {@code IdempotencyWebFilter}: a {@link WebFilter} bean in a non-web
 * {@code @SpringBootTest} context ({@code web-application-type=none}) would otherwise fail
 * context load.
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
@ConditionalOnProperty(name = "kmosf.audit.access-tracking.enabled", havingValue = "true")
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 3)
@RequiredArgsConstructor
public class AccessAuditWebFilter implements WebFilter {

    /** Path prefixes excluded from access auditing (infra / observability / docs — never PHI). */
    private static final Set<String> EXCLUDED_PREFIXES = Set.of(
            "/actuator", "/v3/api-docs", "/openapi", "/webjars", "/favicon");

    private final AccessAuditEventWriter writer;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (isExcluded(exchange.getRequest().getPath().value())) {
            return chain.filter(exchange);
        }
        // Record after the chain completes so the final status is known. record() reads the
        // TenantContext from the Reactor context (written by TenantWebFilter, +1) and no-ops
        // for unauthenticated requests; it swallows its own errors so auditing can never
        // break the request.
        return chain.filter(exchange)
                .then(Mono.defer(() -> writer.record(exchange)));
    }

    private boolean isExcluded(String path) {
        for (String prefix : EXCLUDED_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
