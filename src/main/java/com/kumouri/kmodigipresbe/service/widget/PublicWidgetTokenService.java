package com.kumouri.kmodigipresbe.service.widget;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Issues and verifies HMAC-SHA256-signed widget tokens. Tenants paste the token
 * into their public website's widget snippet; the {@code /public/widget/**} chain
 * accepts the token as the sole auth signal for anonymous POSTs that will create
 * tenant-scoped data (bookings, form submissions, service requests).
 *
 * <h2>Token format</h2>
 * <pre>{@code
 *   token   = base64url(payload) "." base64url(signature)
 *   payload = tenantId "|" widgetType "|" expiresAtEpochSeconds
 *   sig     = HMAC-SHA256(secret, payload-bytes)
 * }</pre>
 * Compact, debuggable (decode the base64url and you see the claims), no JSON deps.
 *
 * <h2>Signing secret</h2>
 * Read from {@code kmosf.security.widget-token-secret}. If unset, a fresh
 * random 32-byte secret is generated at boot and a WARN is logged — dev boots,
 * but issued tokens are invalidated on restart. Production must set the env var.
 *
 * <p>Error code {@code 1600} (token verification failure) per the §8 reservation
 * for Phase 9b widget rejections. The body of the {@link DigiPresBeException}
 * distinguishes the failure mode for logs; the HTTP response is always 401.
 */
@Slf4j
@Service
public class PublicWidgetTokenService {

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;

    public PublicWidgetTokenService(
            @Value("${kmosf.security.widget-token-secret:}") String configured,
            ObjectProvider<Clock> clockProvider) {
        // Tests inject a fixed Clock; in prod no Clock bean exists and we fall back
        // to systemUTC. Keeping this off the heap of mandatory beans means we don't
        // need a Clock @Bean tucked into a config class.
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        if (configured == null || configured.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
            log.warn("kmosf.security.widget-token-secret is unset; using a fresh random secret. "
                    + "Issued tokens will be invalidated on restart. Set the env var for production.");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Issues a token good for {@code ttl} from now. */
    public String issue(UUID tenantId, String widgetType, Duration ttl) {
        if (tenantId == null) {
            throw new DigiPresBeException("tenantId is required", 1600, 400);
        }
        if (widgetType == null || widgetType.isBlank()) {
            throw new DigiPresBeException("widgetType is required", 1600, 400);
        }
        Instant expiresAt = clock.instant().plus(ttl);
        String payload = tenantId + "|" + widgetType + "|" + expiresAt.getEpochSecond();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String sig = ENC.encodeToString(sign(payloadBytes));
        return ENC.encodeToString(payloadBytes) + "." + sig;
    }

    /** Parses + verifies a token. Throws {@link DigiPresBeException} on any failure. */
    public PublicWidgetToken verify(String token) {
        if (token == null || token.isBlank()) {
            throw new DigiPresBeException("Widget token missing", 1600, 401);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new DigiPresBeException("Widget token malformed", 1601, 401);
        }
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            payloadBytes = DEC.decode(token.substring(0, dot));
            providedSig = DEC.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("Widget token not base64url", 1601, 401);
        }
        byte[] expectedSig = sign(payloadBytes);
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new DigiPresBeException("Widget token signature invalid", 1602, 401);
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|");
        if (parts.length != 3) {
            throw new DigiPresBeException("Widget token payload malformed", 1601, 401);
        }
        UUID tenantId;
        Instant expiresAt;
        try {
            tenantId = UUID.fromString(parts[0]);
            expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[2]));
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("Widget token payload malformed", 1601, 401);
        }
        if (expiresAt.isBefore(clock.instant())) {
            throw new DigiPresBeException("Widget token expired", 1603, 401);
        }
        return new PublicWidgetToken(tenantId, parts[1], expiresAt);
    }

    private byte[] sign(byte[] payloadBytes) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALG);
            mac.init(new SecretKeySpec(secret, HMAC_ALG));
            return mac.doFinal(payloadBytes);
        } catch (java.security.GeneralSecurityException ex) {
            throw new IllegalStateException("HMAC-SHA256 unavailable on this JVM", ex);
        }
    }
}
