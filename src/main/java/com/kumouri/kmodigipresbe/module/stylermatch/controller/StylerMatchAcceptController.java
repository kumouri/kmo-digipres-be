package com.kumouri.kmodigipresbe.module.stylermatch.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchResponse;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchBookingService;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
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
 * T12 (Salon "StylerMatch") — the public client accept endpoint. After seeing the ranked stylist board,
 * the client accepts; the BE books a real salon {@code Booking} with the chosen stylist + the requested
 * service and texts a booking link (per-tenant, no live Cal.com), handing the salon a matched, qualified
 * client. The stylist-side twin of the T9 {@code StyleConsultAcceptController}.
 *
 * <h2>Token-scoped, tenant-from-token (the intake precedent)</h2>
 * {@code POST /public/integrations/stylermatch/{token}/matches/{matchId}/accept}. The {@code {token}} is
 * the same {@code PublicWidgetTokenService} {@code "styler-match"} HMAC token; the tenant is resolved
 * from the token <strong>only</strong> (a wrong widgetType &rarr; {@code 4480}/401), then
 * {@link StylerMatchBookingService#accept} runs under the synthetic widget context. So a client can only
 * accept a match in the tenant their token was issued for. The optional {@code staffMemberId} query param
 * chooses which ranked stylist to book (default: the top rank).
 *
 * <h2>Idempotent (service-level explicit-boolean — NOT {@code @IdempotentRoute})</h2>
 * The {@code @IdempotentRoute} middleware ({@code IdempotencyWebFilter}) resolves the
 * {@code TenantContext} at <em>filter</em> time — before the controller establishes the synthetic tenant
 * context from the token — so it cannot be used on a token-authed public route (the T8/T9 documented
 * limitation). Idempotency therefore lives in {@link StylerMatchBookingService#accept}'s
 * <strong>explicit-boolean</strong> guard: a match already BOOKED ({@code bookingId != null}) re-confirms
 * and creates no second {@code Booking}/SMS. {@code 4485} if the match doesn't exist for the tenant;
 * {@code 4483} if no rankable stylist can be resolved.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.chairfill", name="enabled")} — the salon flagship
 * key, default OFF.
 */
@RestController
@RequestMapping("/public/integrations/stylermatch")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class StylerMatchAcceptController {

    private final PublicWidgetTokenService tokens;
    private final StylerMatchBookingService bookingService;

    public StylerMatchAcceptController(PublicWidgetTokenService tokens,
                                       StylerMatchBookingService bookingService) {
        this.tokens = tokens;
        this.bookingService = bookingService;
    }

    @PostMapping("/{token}/matches/{matchId}/accept")
    public Mono<StylerMatchResponse> accept(@PathVariable String token,
                                            @PathVariable UUID matchId,
                                            @RequestParam(value = "staffMemberId", required = false)
                                            UUID staffMemberId) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!StylerMatchService.WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<StylerMatchResponse>error(new DigiPresBeException(
                                "Styler-match token type mismatch (expected '"
                                        + StylerMatchService.WIDGET_TYPE + "', got '"
                                        + claims.widgetType() + "')", 4480, 401));
                    }
                    UUID tenantId = claims.tenantId();
                    TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
                    return bookingService.accept(tenantId, matchId, staffMemberId)
                            .map(StylerMatchResponse::from)
                            .contextWrite(TenantContextHolder.write(anon));
                });
    }
}
