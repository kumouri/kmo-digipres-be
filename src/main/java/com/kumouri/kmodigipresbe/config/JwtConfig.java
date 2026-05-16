package com.kumouri.kmodigipresbe.config;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import com.nimbusds.jwt.proc.JWTProcessor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

/**
 * JWT signing and decoding configuration.
 *
 * <h2>Auth-mode switch (Phase A)</h2>
 * Exactly one {@link ReactiveJwtDecoder} bean is active at a time, selected by
 * {@code kmosf.auth.mode}:
 * <ul>
 *   <li>{@code local} (default, {@code matchIfMissing=true}) — current HS256 decoder
 *       using the {@code kmosf.jwt.secret}. No Zitadel config required.</li>
 *   <li>{@code zitadel} — JWKS-backed decoder via
 *       {@link NimbusReactiveJwtDecoder#withJwkSetUri}. Requires
 *       {@code kmosf.auth.zitadel.jwks-uri} to be non-blank; blank URI fails fast
 *       at startup with {@link IllegalStateException} (no silent half-broken state).</li>
 * </ul>
 *
 * <p>Both {@link SecurityConfig} and {@link PortalSecurityConfig} inject
 * {@code ReactiveJwtDecoder} by type — swapping the impl by property is clean and
 * touches neither security chain.
 *
 * <p>{@link JwtTokenService} (HS256 mint) and {@link com.kumouri.kmodigipresbe.tenancy.JwtTenantResolver}
 * (reads {@code tid}/{@code uid}/{@code roles}/{@code portal} claims) are unaffected
 * by this switch in Phase A. A Zitadel token without those claims will fail tenant
 * resolution with errorCode 1002 — that is expected for Phase A; Phase A2 adds
 * full Zitadel claim mapping.
 */
@Slf4j
@Configuration
@EnableConfigurationProperties({JwtProperties.class, AuthModeProperties.class})
public class JwtConfig {

    private static final String ALG = "HmacSHA256";

    @Bean
    public SecretKeySpec jwtSigningKey(JwtProperties props) {
        String secret = props.secret();
        if (secret == null || secret.isBlank()) {
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            log.warn("KMOSF_JWT_SECRET not set — using an ephemeral random secret. " +
                    "All tokens will be invalidated on every restart. " +
                    "Set KMOSF_JWT_SECRET to a 32+ character value before production use.");
            return new SecretKeySpec(random, ALG);
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException(
                    "kmosf.jwt.secret must be at least 32 bytes for HS256");
        }
        return new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), ALG);
    }

    /**
     * Local HS256 decoder — active when {@code kmosf.auth.mode=local} (or unset).
     * This is the default and the only mode that boots without any Zitadel config.
     */
    @Bean
    @ConditionalOnProperty(name = "kmosf.auth.mode", havingValue = "local", matchIfMissing = true)
    public ReactiveJwtDecoder reactiveJwtDecoder(SecretKeySpec key) {
        log.info("JWT auth mode: local (HS256)");
        return NimbusReactiveJwtDecoder.withSecretKey(key)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Zitadel JWKS decoder — active when {@code kmosf.auth.mode=zitadel}.
     * Fails fast at startup if {@code kmosf.auth.zitadel.jwks-uri} is blank.
     *
     * <p>Phase A: the decoder is wired but {@code JwtTenantResolver} still reads
     * {@code tid}/{@code uid}/{@code roles} claims — a Zitadel token without those
     * claims fails with errorCode 1002. Full Zitadel federation is Phase A2.
     */
    @Bean
    @ConditionalOnProperty(name = "kmosf.auth.mode", havingValue = "zitadel")
    public ReactiveJwtDecoder zitadelJwtDecoder(AuthModeProperties authModeProperties) {
        String jwksUri = authModeProperties.zitadel().jwksUri();
        if (jwksUri == null || jwksUri.isBlank()) {
            throw new IllegalStateException(
                    "kmosf.auth.mode=zitadel requires kmosf.auth.zitadel.jwks-uri to be set. " +
                    "Set KMOSF_AUTH_ZITADEL_JWKS_URI or switch back to kmosf.auth.mode=local.");
        }
        log.info("JWT auth mode: zitadel (JWKS from {})", jwksUri);
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource(SecretKeySpec key) {
        return new ImmutableSecret<>(key.getEncoded());
    }

    @Bean
    public JWTProcessor<SecurityContext> jwtProcessor(JWKSource<SecurityContext> jwkSource) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.HS256, jwkSource));
        return processor;
    }
}
