package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.config.PortalSecurityConfig.OAuthRegistrationsSummary;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;

/**
 * Read-only OAuth endpoints. The actual OAuth dance is handled by Spring Security's
 * filters wired in {@link com.kumouri.kmodigipresbe.config.PortalSecurityConfig}; this
 * controller exists to tell the FE which "Continue with X" buttons to render.
 */
@RestController
@RequestMapping("/portal/auth")
@RequiredArgsConstructor
public class PortalOAuthController {

    private static final String AUTHORIZE_TEMPLATE =
            "/portal/auth/oauth2/authorization/%s";

    private final OAuthRegistrationsSummary summary;

    @GetMapping("/providers")
    public Mono<ProvidersResponse> providers() {
        List<Provider> out = new ArrayList<>();
        if (summary.googleEnabled()) {
            out.add(new Provider("google", "Google",
                    String.format(AUTHORIZE_TEMPLATE, "google")));
        }
        if (summary.microsoftEnabled()) {
            out.add(new Provider("microsoft", "Microsoft",
                    String.format(AUTHORIZE_TEMPLATE, "microsoft")));
        }
        return Mono.just(new ProvidersResponse(out));
    }

    public record ProvidersResponse(List<Provider> providers) {}

    public record Provider(String id, String displayName, String authorizeUrl) {}
}
