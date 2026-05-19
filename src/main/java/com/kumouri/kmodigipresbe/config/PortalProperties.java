package com.kumouri.kmodigipresbe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Configuration for the client portal (Phase 5). OAuth providers are configured under
 * {@code kmosf.portal.oauth.*} and bound by {@link OAuthClientRegistrationConfig}
 * rather than Spring Boot's stock {@code spring.security.oauth2.client.*} namespace —
 * that lets the app boot without any OAuth credentials configured (e.g. dev/test).
 */
@ConfigurationProperties(prefix = "kmosf.portal")
public record PortalProperties(
        boolean enabled,
        String baseHost,
        String successRedirect,
        String failureRedirect,
        String signupPolicyDefault,
        long invitationTtlHours,
        long magicLinkTtlMinutes,
        String jwtCookieName,
        boolean jwtCookieSecure,
        String jwtCookieDomain,
        WebAuthn webAuthn,
        Oauth oauth) {

    public record WebAuthn(
            String rpId,
            String rpName,
            List<String> allowedOrigins,
            long challengeTtlSeconds) {
    }

    public record Oauth(Provider google, Provider microsoft, Provider zitadel) {
        public record Provider(String clientId, String clientSecret, String issuerUri) {
            public boolean enabled() {
                return clientId != null && !clientId.isBlank()
                        && clientSecret != null && !clientSecret.isBlank();
            }
        }
    }
}
