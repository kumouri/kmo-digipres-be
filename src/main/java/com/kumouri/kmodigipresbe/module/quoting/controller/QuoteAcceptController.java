package com.kumouri.kmodigipresbe.module.quoting.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.quoting.controller.dto.QuoteResponse;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteBookingService;
import com.kumouri.kmodigipresbe.module.quoting.service.QuoteIntakeService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — the public homeowner accept endpoint. After seeing the instant
 * quote the homeowner accepts it; the BE texts a booking link (per-tenant, no live Cal.com) and hands
 * the office a pre-qualified job.
 *
 * <h2>Token-scoped, tenant-from-token (the intake precedent)</h2>
 * {@code POST /public/integrations/quoting/{token}/quotes/{quoteId}/accept}. The {@code {token}} is
 * the same {@code PublicWidgetTokenService} {@code "quote-intake"} HMAC token; the tenant is resolved
 * from the token <strong>only</strong> (a wrong widgetType → {@code 4430}/401), then
 * {@link QuoteBookingService#accept} runs the booking under the synthetic widget context. So the
 * homeowner can only accept a quote in the tenant their token was issued for.
 *
 * <h2>Idempotent (service-level — NOT {@code @IdempotentRoute})</h2>
 * This is a <strong>public token route</strong>: it carries no JWT, so the {@code TenantContext} is set
 * by this handler from the token, AFTER the {@code IdempotencyWebFilter} runs. That filter does
 * {@code TenantContextHolder.required()} up front, so {@code @IdempotentRoute} here would fail before the
 * handler ever resolves the tenant (the StyleConsult accept hit + documents the same trap). Idempotency
 * is therefore the booking-service's own explicit-boolean guard: a re-accept re-confirms the existing
 * booking with no second SMS (proven by {@code QuoteAcceptIT}). {@code 4435} if the quote doesn't exist
 * for the tenant; {@code 4436} if it's DECLINED.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.quoting", name="enabled")} — default OFF; this
 * public route IS in the OpenAPI spec when the module is on at generation time.
 */
@RestController
@RequestMapping("/public/integrations/quoting")
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class QuoteAcceptController {

    private final PublicWidgetTokenService tokens;
    private final QuoteBookingService bookingService;

    public QuoteAcceptController(PublicWidgetTokenService tokens, QuoteBookingService bookingService) {
        this.tokens = tokens;
        this.bookingService = bookingService;
    }

    @PostMapping("/{token}/quotes/{quoteId}/accept")
    public Mono<QuoteResponse> accept(@PathVariable String token, @PathVariable UUID quoteId) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!QuoteIntakeService.WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<QuoteResponse>error(new DigiPresBeException(
                                "Quote-intake token type mismatch (expected '"
                                        + QuoteIntakeService.WIDGET_TYPE + "', got '"
                                        + claims.widgetType() + "')", 4430, 401));
                    }
                    UUID tenantId = claims.tenantId();
                    TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
                    return bookingService.accept(tenantId, quoteId)
                            .map(QuoteResponse::from)
                            .contextWrite(TenantContextHolder.write(anon));
                });
    }
}
