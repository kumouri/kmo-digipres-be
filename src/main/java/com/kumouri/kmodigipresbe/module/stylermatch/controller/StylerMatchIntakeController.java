package com.kumouri.kmodigipresbe.module.stylermatch.controller;

import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchRequestBody;
import com.kumouri.kmodigipresbe.module.stylermatch.controller.dto.StylerMatchResponse;
import com.kumouri.kmodigipresbe.module.stylermatch.model.MatchRequest;
import com.kumouri.kmodigipresbe.module.stylermatch.service.StylerMatchService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * T12 (Salon "StylerMatch") — the public client intake endpoint. A new client (from the salon's website
 * widget or a QR/Instagram-bio link) submits the service + style they want (+ optionally a slot and a
 * preferred stylist) as a JSON body; the BE returns a ranked, explained stylist board in milliseconds,
 * then a path to book.
 *
 * <h2>One public endpoint, tenant-from-token (the T9 {@code StyleConsultIntakeController} precedent)</h2>
 * {@code POST /public/integrations/stylermatch/{token}/match}. The {@code {token}} is a
 * {@code PublicWidgetTokenService} HMAC token (widgetType {@code "styler-match"});
 * {@link StylerMatchService} verifies it and resolves the tenant from the token <strong>only</strong> —
 * never the body. The {@code contactId} field of the body is <strong>ignored on the public path</strong>
 * (a stranger must not be able to bind a match to an arbitrary contact); the client is found-or-created
 * from the supplied phone/email instead. Reached via the existing {@code /public/**} permitAll rule.
 *
 * <h2>JSON body, NOT multipart</h2>
 * A text/attribute match needs no photo, so the body is plain JSON ({@link StylerMatchRequestBody}) —
 * unlike the T8/T9 photo intakes there is no {@code getMultipartData} and no AI/vision call here.
 *
 * <h2>Module gate</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.chairfill", name="enabled")} — the salon flagship
 * key, default OFF; when the module is disabled the bean is absent &rarr; endpoint not registered &rarr;
 * 404 (the {@code ServiceRequestWidgetController}/T9 "correct outcome").
 */
@RestController
@RequestMapping("/public/integrations/stylermatch")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class StylerMatchIntakeController {

    private final StylerMatchService matchService;

    public StylerMatchIntakeController(StylerMatchService matchService) {
        this.matchService = matchService;
    }

    @PostMapping("/{token}/match")
    public Mono<StylerMatchResponse> match(@PathVariable String token,
                                           @RequestBody StylerMatchRequestBody body) {
        StylerMatchRequestBody safe = body == null
                ? new StylerMatchRequestBody(null, null, null, null, null, null, null, null, null,
                        null, null, null, null)
                : body;
        // Public path: ignore any client-supplied contactId — the contact is found-or-created from
        // the phone/email, so a stranger cannot bind a match to another tenant's contact.
        MatchRequest req = safe.toMatchRequest().toBuilder().contactId(null).build();
        return matchService.submitPublic(token, req, safe.toManualContact())
                .map(StylerMatchResponse::from);
    }
}
