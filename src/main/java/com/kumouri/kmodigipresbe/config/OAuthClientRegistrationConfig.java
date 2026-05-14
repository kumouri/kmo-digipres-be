package com.kumouri.kmodigipresbe.config;

import com.kumouri.kmodigipresbe.config.PortalSecurityConfig.OAuthRegistrationsSummary;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.client.oidc.authentication.ReactiveOidcIdTokenDecoderFactory;
import org.springframework.security.oauth2.client.oidc.authentication.OidcIdTokenValidator;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrations;
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoderFactory;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Programmatic OAuth/OIDC client registration. We avoid Spring Boot's
 * {@code spring.security.oauth2.client.*} property binding because it fails at startup
 * if any registration has a blank {@code client-id}; this lets dev/test profiles boot
 * cleanly when no OAuth credentials are configured, and lets the portal chain skip
 * {@code oauth2Login()} altogether in that case.
 * <p>Microsoft uses {@code /common/v2.0} (multi-tenant Entra + personal MSA). Tokens
 * carry a per-Azure-tenant {@code iss} of the form
 * {@code https://login.microsoftonline.com/{guid}/v2.0} which Spring's default
 * {@link OidcIdTokenValidator} would reject; the {@link #idTokenDecoderFactory} bean
 * swaps in a permissive validator chain for that one registration.
 */
@Slf4j
@Configuration
public class OAuthClientRegistrationConfig {

    /** Matches {@code https://login.microsoftonline.com/{azureTenantId}/v2.0}. */
    private static final Pattern MICROSOFT_ISSUER_PATTERN = Pattern.compile(
            "^https://login\\.microsoftonline\\.com/[a-f0-9-]{8,}/v2\\.0$");

    @Bean
    public ReactiveClientRegistrationRepository clientRegistrationRepository(
            PortalProperties props) {
        List<ClientRegistration> registrations = new ArrayList<>();
        if (props.oauth() != null && props.oauth().google() != null
                && props.oauth().google().enabled()) {
            registrations.add(googleRegistration(props.oauth().google()));
            log.info("OAuth provider 'google' is configured for the client portal.");
        }
        if (props.oauth() != null && props.oauth().microsoft() != null
                && props.oauth().microsoft().enabled()) {
            registrations.add(microsoftRegistration(props.oauth().microsoft()));
            log.info("OAuth provider 'microsoft' is configured for the client portal.");
        }
        if (registrations.isEmpty()) {
            log.info("No OAuth providers configured; portal will offer magic-link + passkey only.");
            // InMemoryReactiveClientRegistrationRepository's constructor rejects an empty
            // list (Assert.notEmpty), so return a no-op repository directly. This is what
            // makes the "boot cleanly when no OAuth credentials are configured" promise in
            // this class's javadoc actually hold.
            return registrationId -> Mono.empty();
        }
        return new InMemoryReactiveClientRegistrationRepository(registrations);
    }

    @Bean
    public OAuthRegistrationsSummary oauthRegistrationsSummary(PortalProperties props) {
        boolean google = props.oauth() != null && props.oauth().google() != null
                && props.oauth().google().enabled();
        boolean microsoft = props.oauth() != null && props.oauth().microsoft() != null
                && props.oauth().microsoft().enabled();
        return new OAuthRegistrationsSummary(google, microsoft);
    }

    /**
     * ID token decoder factory. For Microsoft, replaces Spring's default
     * {@link OidcIdTokenValidator} with a chain that omits the strict
     * issuer-equality check and substitutes a Microsoft-family pattern match.
     */
    @Bean
    public ReactiveJwtDecoderFactory<ClientRegistration> idTokenDecoderFactory() {
        ReactiveOidcIdTokenDecoderFactory factory = new ReactiveOidcIdTokenDecoderFactory();
        factory.setJwtValidatorFactory(registration -> {
            if ("microsoft".equals(registration.getRegistrationId())) {
                return new DelegatingOAuth2TokenValidator<>(
                        new JwtTimestampValidator(),
                        new MicrosoftIssuerValidator());
            }
            return new OidcIdTokenValidator(registration);
        });
        return factory;
    }

    private ClientRegistration googleRegistration(PortalProperties.Oauth.Provider props) {
        return ClientRegistrations.fromIssuerLocation("https://accounts.google.com")
                .registrationId("google")
                .clientId(props.clientId())
                .clientSecret(props.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/portal/auth/oauth2/callback/{registrationId}")
                .scope("openid", "email", "profile")
                .clientName("Google")
                .build();
    }

    private ClientRegistration microsoftRegistration(PortalProperties.Oauth.Provider props) {
        String issuer = props.issuerUri() != null && !props.issuerUri().isBlank()
                ? props.issuerUri()
                : "https://login.microsoftonline.com/common/v2.0";
        return ClientRegistrations.fromIssuerLocation(issuer)
                .registrationId("microsoft")
                .clientId(props.clientId())
                .clientSecret(props.clientSecret())
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("{baseUrl}/portal/auth/oauth2/callback/{registrationId}")
                .scope("openid", "email", "profile")
                .userNameAttributeName("sub")
                .clientName("Microsoft")
                .build();
    }

    /**
     * Accepts any {@code iss} matching the Microsoft v2.0 tenant family. Used in place
     * of Spring's default per-registration issuer-equality check, which would reject
     * tokens whose {@code iss} embeds the per-Azure-tenant GUID rather than literal
     * {@code common}.
     */
    static class MicrosoftIssuerValidator implements OAuth2TokenValidator<Jwt> {
        @Override
        public OAuth2TokenValidatorResult validate(Jwt token) {
            String iss = token.getIssuer() == null ? null : token.getIssuer().toString();
            if (iss != null && MICROSOFT_ISSUER_PATTERN.matcher(iss).matches()) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_issuer",
                    "Issuer " + iss + " is not a recognized Microsoft v2.0 endpoint",
                    null));
        }
    }
}
