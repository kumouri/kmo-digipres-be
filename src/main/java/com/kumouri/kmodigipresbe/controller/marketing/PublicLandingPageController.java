package com.kumouri.kmodigipresbe.controller.marketing;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.marketing.LandingPageRenderService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Anonymous public endpoint for tenant landing pages.
 * URL: {@code GET /public/p/{tenantSlug}/{pageSlug}}
 *
 * <p>The tenant slug is resolved via {@link TenantRepository#findBySlug(String)}
 * so that the landing page can be accessed without authentication and without
 * subdomain routing (suitable for embed links, QR codes, social media posts).
 *
 * <p>Returns rendered HTML. Unpublished pages and unknown slugs return 404.
 */
@RestController
@RequestMapping("/public/p")
@RequiredArgsConstructor
public class PublicLandingPageController {

    private final TenantRepository tenants;
    private final LandingPageRenderService renderService;

    @GetMapping(value = "/{tenantSlug}/{pageSlug}", produces = MediaType.TEXT_HTML_VALUE)
    public Mono<String> render(@PathVariable String tenantSlug,
                                @PathVariable String pageSlug) {
        return tenants.findBySlug(tenantSlug.toLowerCase())
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Unknown tenant: " + tenantSlug, 1201, 404)))
                .flatMap(tenant -> renderService.render(tenant.getId(), pageSlug));
    }
}
