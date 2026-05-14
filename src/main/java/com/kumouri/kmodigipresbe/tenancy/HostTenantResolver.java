package com.kumouri.kmodigipresbe.tenancy;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.util.Set;

/**
 * Resolves a {@link Tenant} for a request that is not yet authenticated — i.e. portal
 * pre-auth endpoints where the JWT-based {@link JwtTenantResolver} cannot fire yet.
 * <p>Three resolution mechanisms in priority order:
 * <ol>
 *   <li>{@code X-Tenant-Slug} request header (dev/test override).</li>
 *   <li>{@code tenant} query parameter (for the OAuth state round-trip; pre-callback).</li>
 *   <li>Subdomain of the {@code Host} header — {@code acme.crm.kmosf.dev} resolves to
 *       tenant slug {@code acme}.</li>
 * </ol>
 * Reserved subdomains ({@code www}, {@code api}, {@code admin}) are not treated as
 * tenant slugs.
 */
@Component
@RequiredArgsConstructor
public class HostTenantResolver {

    public static final String SLUG_HEADER = "X-Tenant-Slug";
    public static final String SLUG_QUERY_PARAM = "tenant";

    private static final Set<String> RESERVED_SUBDOMAINS =
            Set.of("www", "api", "admin", "app");

    private final TenantRepository tenants;

    public Mono<Tenant> resolve(ServerWebExchange exchange) {
        String slug = extractSlug(exchange);
        if (slug == null) {
            return Mono.error(new DigiPresBeException(
                    "Tenant cannot be resolved from request — set Host subdomain, "
                            + "?tenant query param, or X-Tenant-Slug header.",
                    1200, 400));
        }
        return tenants.findBySlug(slug)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Unknown tenant: " + slug, 1201, 404)));
    }

    public Mono<TenantContext> resolveAsContext(ServerWebExchange exchange) {
        return resolve(exchange).map(t -> new TenantContext(t.getId(), null, Set.of()));
    }

    private String extractSlug(ServerWebExchange exchange) {
        HttpHeaders headers = exchange.getRequest().getHeaders();
        String headerSlug = headers.getFirst(SLUG_HEADER);
        if (headerSlug != null && !headerSlug.isBlank()) {
            return headerSlug.toLowerCase();
        }
        String querySlug = exchange.getRequest().getQueryParams().getFirst(SLUG_QUERY_PARAM);
        if (querySlug != null && !querySlug.isBlank()) {
            return querySlug.toLowerCase();
        }
        String host = headers.getFirst(HttpHeaders.HOST);
        if (host == null || host.isBlank()) return null;
        String hostOnly = host.split(":", 2)[0];
        int firstDot = hostOnly.indexOf('.');
        if (firstDot <= 0) return null;
        String subdomain = hostOnly.substring(0, firstDot).toLowerCase();
        if (RESERVED_SUBDOMAINS.contains(subdomain)) return null;
        return subdomain;
    }
}
