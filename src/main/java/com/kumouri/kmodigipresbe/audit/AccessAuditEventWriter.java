package com.kumouri.kmodigipresbe.audit;

import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Persists {@link AccessAuditEvent}s for {@link AccessAuditWebFilter}. Reads the tenant
 * and actor from the Reactor {@link TenantContextHolder} context (written by
 * {@code TenantWebFilter}), so it records only authenticated, tenant-scoped requests;
 * anonymous / pre-auth requests (no tenant context) are silently skipped.
 *
 * <p>Always available as a bean — cheap and idle unless the (feature-gated) filter calls
 * it. On a write failure it logs and swallows the error, exactly like
 * {@link AuditEventWriter}: an audit-log blip must never break the user's request.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessAuditEventWriter {

    private static final Pattern UUID_SEGMENT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final AccessAuditEventRepository repository;

    /**
     * Records one access event for the just-completed exchange. No-ops ({@code Mono.empty})
     * when there is no tenant context (unauthenticated / public path).
     */
    public Mono<Void> record(ServerWebExchange exchange) {
        return TenantContextHolder.current()
                .map(ctx -> build(ctx.tenantId(), ctx.userId(), exchange))
                .flatMap(repository::save)
                .doOnError(err -> log.warn("Failed to write access-audit event for {} {}",
                        exchange.getRequest().getMethod(),
                        exchange.getRequest().getPath().value(), err))
                .onErrorResume(err -> Mono.empty())
                .then();
    }

    private AccessAuditEvent build(UUID tenantId, UUID actorUserId, ServerWebExchange exchange) {
        ServerHttpRequest req = exchange.getRequest();
        ServerHttpResponse res = exchange.getResponse();
        String path = req.getPath().value();
        int status = res.getStatusCode() != null ? res.getStatusCode().value() : 0;

        return AccessAuditEvent.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .actorUserId(actorUserId)
                .method(req.getMethod().name())
                .path(path)
                .resourceType(resourceType(path))
                .resourceId(resourceId(path))
                .statusCode(status)
                .requestId(req.getId())
                .remoteAddr(remoteAddr(req))
                .at(Instant.now())
                .build();
    }

    /** First meaningful path segment, skipping any {@code /api/v1} base-path remnants. */
    static String resourceType(String path) {
        for (String seg : segments(path)) {
            if (seg.equals("api") || seg.equals("v1")) {
                continue;
            }
            return seg;
        }
        return null;
    }

    /** First UUID-looking path segment, or null for collection / list access. */
    static String resourceId(String path) {
        for (String seg : segments(path)) {
            if (UUID_SEGMENT.matcher(seg).matches()) {
                return seg;
            }
        }
        return null;
    }

    private static String[] segments(String path) {
        if (path == null || path.isBlank()) {
            return new String[0];
        }
        String trimmed = path.startsWith("/") ? path.substring(1) : path;
        return trimmed.isBlank() ? new String[0] : trimmed.split("/");
    }

    private static String remoteAddr(ServerHttpRequest req) {
        InetSocketAddress addr = req.getRemoteAddress();
        return addr != null && addr.getAddress() != null
                ? addr.getAddress().getHostAddress() : null;
    }
}
