package com.kumouri.kmodigipresbe.integration.moletripwire;

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
 * Issues and verifies HMAC-SHA256-signed <strong>mole-tripwire</strong> tokens (Phase 3 — NMM
 * coverage-window automation, the B2 re-activity tripwire). A new <strong>additive sibling</strong>
 * of {@link com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService}: same compact
 * base64url HMAC scheme, same signing secret ({@code kmosf.security.widget-token-secret}), same
 * constant-time verify — but the payload carries one extra claim, the coverage customer's
 * <strong>{@code projectId}</strong>, which the Phase-2 3-field token cannot hold. The Phase-2
 * {@code PublicWidgetTokenService} therefore stays empty-diff (this is a strictly-additive sibling,
 * not a modification — the same posture as the Phase-1 {@code VoicemailExtractionService} mirroring
 * {@code AnthropicAiAssistService}).
 *
 * <h2>Token format</h2>
 * <pre>{@code
 *   token   = base64url(payload) "." base64url(signature)
 *   payload = tenantId "|" widgetType "|" projectId "|" expiresAtEpochSeconds
 *   sig     = HMAC-SHA256(secret, payload-bytes)
 * }</pre>
 * Compact, debuggable, no JSON deps — the verbatim {@code PublicWidgetTokenService} format with the
 * {@code projectId} field inserted between {@code widgetType} and the expiry.
 *
 * <h2>Signing secret</h2>
 * Read from {@code kmosf.security.widget-token-secret} (shared with the Phase-2 widget tokens — one
 * per-tenant-deployment secret). If unset, a fresh random 32-byte secret is generated at boot and a
 * WARN is logged — dev boots, but issued tokens are invalidated on restart. Production must set the
 * env var.
 *
 * <p>Generic token rejections reuse the established {@code 1600-1603} range (missing / malformed /
 * bad-signature / expired) — the same codes {@code PublicWidgetTokenService} uses — so the tripwire
 * shares the documented public-widget-token-rejection vocabulary. A wrong {@code widgetType} is
 * surfaced by the caller ({@link MoleTripwireService}) as {@code 4013}.
 */
@Slf4j
@Service
public class MoleTripwireTokenService {

    /** The {@code widgetType} claim a mole-tripwire token must carry. */
    public static final String WIDGET_TYPE = "mole-tripwire";

    private static final String HMAC_ALG = "HmacSHA256";
    private static final Base64.Encoder ENC = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DEC = Base64.getUrlDecoder();

    private final byte[] secret;
    private final Clock clock;

    public MoleTripwireTokenService(
            @Value("${kmosf.security.widget-token-secret:}") String configured,
            ObjectProvider<Clock> clockProvider) {
        // Tests inject a fixed Clock; in prod no Clock bean exists and we fall back to systemUTC —
        // the verbatim PublicWidgetTokenService posture.
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
        if (configured == null || configured.isBlank()) {
            byte[] generated = new byte[32];
            new SecureRandom().nextBytes(generated);
            this.secret = generated;
            log.warn("kmosf.security.widget-token-secret is unset; using a fresh random secret for "
                    + "mole-tripwire tokens. Issued tokens will be invalidated on restart. Set the "
                    + "env var for production.");
        } else {
            this.secret = configured.getBytes(StandardCharsets.UTF_8);
        }
    }

    /** Issues a mole-tripwire token for {@code projectId}, good for {@code ttl} from now. */
    public String issue(UUID tenantId, UUID projectId, Duration ttl) {
        if (tenantId == null) {
            throw new DigiPresBeException("tenantId is required", 1600, 400);
        }
        if (projectId == null) {
            throw new DigiPresBeException("projectId is required", 1600, 400);
        }
        Instant expiresAt = clock.instant().plus(ttl);
        String payload = tenantId + "|" + WIDGET_TYPE + "|" + projectId + "|"
                + expiresAt.getEpochSecond();
        byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
        String sig = ENC.encodeToString(sign(payloadBytes));
        return ENC.encodeToString(payloadBytes) + "." + sig;
    }

    /** Parses + verifies a token. Throws {@link DigiPresBeException} on any failure. */
    public MoleTripwireToken verify(String token) {
        if (token == null || token.isBlank()) {
            throw new DigiPresBeException("Tripwire token missing", 1600, 401);
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot == token.length() - 1) {
            throw new DigiPresBeException("Tripwire token malformed", 1601, 401);
        }
        byte[] payloadBytes;
        byte[] providedSig;
        try {
            payloadBytes = DEC.decode(token.substring(0, dot));
            providedSig = DEC.decode(token.substring(dot + 1));
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("Tripwire token not base64url", 1601, 401);
        }
        byte[] expectedSig = sign(payloadBytes);
        if (!MessageDigest.isEqual(expectedSig, providedSig)) {
            throw new DigiPresBeException("Tripwire token signature invalid", 1602, 401);
        }
        String payload = new String(payloadBytes, StandardCharsets.UTF_8);
        String[] parts = payload.split("\\|");
        if (parts.length != 4) {
            throw new DigiPresBeException("Tripwire token payload malformed", 1601, 401);
        }
        UUID tenantId;
        UUID projectId;
        Instant expiresAt;
        try {
            tenantId = UUID.fromString(parts[0]);
            projectId = UUID.fromString(parts[2]);
            expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[3]));
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("Tripwire token payload malformed", 1601, 401);
        }
        if (expiresAt.isBefore(clock.instant())) {
            throw new DigiPresBeException("Tripwire token expired", 1603, 401);
        }
        return new MoleTripwireToken(tenantId, parts[1], projectId, expiresAt);
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
