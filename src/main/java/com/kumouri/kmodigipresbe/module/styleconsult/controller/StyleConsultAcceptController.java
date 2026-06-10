package com.kumouri.kmodigipresbe.module.styleconsult.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.module.styleconsult.controller.dto.StyleConsultResponse;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultBookingService;
import com.kumouri.kmodigipresbe.module.styleconsult.service.StyleConsultService;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — the public prospect accept endpoint. After seeing the recommended
 * services + retail, the prospect accepts; the BE creates a real salon {@code Booking} for the chosen
 * service and texts a booking link (per-tenant, no live Cal.com), handing the salon a pre-qualified
 * client.
 *
 * <h2>Token-scoped, tenant-from-token (the intake precedent)</h2>
 * {@code POST /public/integrations/styleconsult/{token}/consults/{consultId}/accept}. The
 * {@code {token}} is the same {@code PublicWidgetTokenService} {@code "style-consult"} HMAC token; the
 * tenant is resolved from the token <strong>only</strong> (a wrong widgetType → {@code 4450}/401), then
 * {@link StyleConsultBookingService#accept} runs under the synthetic widget context. So a prospect can
 * only accept a consult in the tenant their token was issued for. The optional
 * {@code serviceMenuItemId} query param chooses which recommended service to book (default: the first).
 *
 * <h2>Idempotent</h2>
 * {@code @IdempotentRoute} (replays the same response for a repeated {@code Idempotency-Key}); the
 * booking-service accept is itself explicit-boolean idempotent (a re-accept re-confirms — no second
 * booking/SMS). {@code 4455} if the consult doesn't exist for the tenant; {@code 4453} if no bookable
 * service can be resolved.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.chairfill", name="enabled")} — the salon flagship
 * key, default OFF.
 */
@RestController
@RequestMapping("/public/integrations/styleconsult")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class StyleConsultAcceptController {

    private final PublicWidgetTokenService tokens;
    private final StyleConsultBookingService bookingService;

    public StyleConsultAcceptController(PublicWidgetTokenService tokens,
                                        StyleConsultBookingService bookingService) {
        this.tokens = tokens;
        this.bookingService = bookingService;
    }

    @PostMapping("/{token}/consults/{consultId}/accept")
    @IdempotentRoute
    public Mono<StyleConsultResponse> accept(@PathVariable String token,
                                             @PathVariable UUID consultId,
                                             @RequestParam(value = "serviceMenuItemId", required = false)
                                             String serviceMenuItemId) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!StyleConsultService.WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<StyleConsultResponse>error(new DigiPresBeException(
                                "Style-consult token type mismatch (expected '"
                                        + StyleConsultService.WIDGET_TYPE + "', got '"
                                        + claims.widgetType() + "')", 4450, 401));
                    }
                    UUID tenantId = claims.tenantId();
                    TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
                    return bookingService.accept(tenantId, consultId, serviceMenuItemId)
                            .map(StyleConsultResponse::from)
                            .contextWrite(TenantContextHolder.write(anon));
                });
    }
}
