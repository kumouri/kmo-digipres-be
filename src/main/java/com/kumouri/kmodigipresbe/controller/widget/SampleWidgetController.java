package com.kumouri.kmodigipresbe.controller.widget;

import com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Demonstration anonymous widget endpoint. Proves the {@link PublicWidgetSecurityConfig}
 * chain wires correctly: the token is verified by {@link PublicWidgetTokenService}
 * and parsed claims are echoed back. Phase 10 (service-request widget) and Phase 12
 * (booking + form widgets) replace this with real entity-creating handlers; this
 * controller stays for smoke testing.
 */
@RestController
@RequestMapping("/public/widget")
@RequiredArgsConstructor
public class SampleWidgetController {

    private final PublicWidgetTokenService tokens;

    @PostMapping("/sample/{token}")
    public Mono<Map<String, Object>> sample(@PathVariable String token) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .map(SampleWidgetController::asMap);
    }

    private static Map<String, Object> asMap(PublicWidgetToken claims) {
        return Map.of(
                "tenantId", claims.tenantId().toString(),
                "widgetType", claims.widgetType(),
                "expiresAt", claims.expiresAt().toString());
    }
}
