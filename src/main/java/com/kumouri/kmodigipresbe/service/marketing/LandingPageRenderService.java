package com.kumouri.kmodigipresbe.service.marketing;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.marketing.LandingPage;
import com.kumouri.kmodigipresbe.repository.marketing.LandingPageRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.samskivert.mustache.Mustache;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.StringReader;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Renders a {@link LandingPage}'s Mustache template. When the page has an
 * associated form, a {@code widgetType="form"} widget token is issued and
 * injected as {@code {{formWidgetToken}}} in the template.
 */
@Service
@RequiredArgsConstructor
public class LandingPageRenderService {

    private static final Duration FORM_TOKEN_TTL = Duration.ofDays(365);

    private final LandingPageRepository pages;
    private final PublicWidgetTokenService widgetTokens;

    /**
     * Looks up the page, checks it is published, then renders its template.
     *
     * @param tenantId tenant uuid
     * @param slug     page slug
     * @return rendered HTML string
     */
    public Mono<String> render(UUID tenantId, String slug) {
        return pages.findByTenantIdAndSlug(tenantId, slug)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Landing page not found: " + slug, 3100, 404)))
                .flatMap(page -> {
                    if (page.getPublishedAt() == null || page.getPublishedAt().isAfter(Instant.now())) {
                        return Mono.error(new DigiPresBeException(
                                "Landing page not published: " + slug, 3101, 404));
                    }
                    return buildContext(tenantId, page)
                            .flatMap(ctx -> Mono.fromCallable(
                                            () -> renderTemplate(page.getMustacheTemplate(), ctx))
                                    .subscribeOn(Schedulers.boundedElastic()));
                });
    }

    private Mono<Map<String, Object>> buildContext(UUID tenantId, LandingPage page) {
        Map<String, Object> ctx = new HashMap<>();
        ctx.put("pageTitle", page.getTitle() != null ? page.getTitle() : "");
        ctx.put("formWidgetToken", "");

        if (page.getFormId() == null) {
            return Mono.just(ctx);
        }
        return Mono.fromCallable(
                        () -> widgetTokens.issue(tenantId, "form", FORM_TOKEN_TTL))
                .subscribeOn(Schedulers.boundedElastic())
                .map(token -> {
                    ctx.put("formWidgetToken", token);
                    ctx.put("formId", page.getFormId().toString());
                    return ctx;
                });
    }

    private String renderTemplate(String template, Map<String, Object> ctx) {
        if (template == null || template.isBlank()) return "";
        return Mustache.compiler().compile(new StringReader(template)).execute(ctx);
    }
}
