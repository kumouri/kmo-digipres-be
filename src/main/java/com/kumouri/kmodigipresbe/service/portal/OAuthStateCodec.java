package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Encodes and verifies the OAuth {@code state} parameter so the callback can map back
 * to a tenant. Tenant identity flows through Google/Microsoft round-trip in {@code state}
 * because we use a single fixed redirect URI per provider (per-tenant subdomains don't
 * scale across Google's 100-URI cap). HMAC-SHA256 over the payload defends against
 * an attacker minting a state for one tenant and pivoting it into another's data.
 * <p>Wire format: {@code base64url(payload).base64url(hmac)}<br>
 * Payload format: {@code <random-nonce>:<tenantId>:<issuedAtEpochSec>}
 */
@Component
@RequiredArgsConstructor
public class OAuthStateCodec {

    private static final String ALG = "HmacSHA256";
    /** Reject state older than 10 minutes — covers OAuth round-trip plus jitter. */
    private static final long MAX_AGE_SECONDS = 600;

    private final SecretKeySpec jwtSigningKey;

    public String encode(UUID tenantId) {
        byte[] nonce = new byte[16];
        new SecureRandom().nextBytes(nonce);
        String payload = base64Url(nonce) + ":" + tenantId + ":" + Instant.now().getEpochSecond();
        String mac = hmac(payload);
        return base64Url(payload.getBytes(StandardCharsets.UTF_8)) + "." + mac;
    }

    public UUID decode(String state) {
        if (state == null || state.isBlank()) {
            throw new DigiPresBeException("Missing OAuth state", 1210, 400);
        }
        int dot = state.indexOf('.');
        if (dot <= 0 || dot == state.length() - 1) {
            throw new DigiPresBeException("Malformed OAuth state", 1211, 400);
        }
        String payloadB64 = state.substring(0, dot);
        String mac = state.substring(dot + 1);
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(payloadB64), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("OAuth state not base64url", 1212, 400);
        }
        if (!constantTimeEquals(mac, hmac(payload))) {
            throw new DigiPresBeException("OAuth state signature mismatch", 1213, 400);
        }
        String[] parts = payload.split(":", 3);
        if (parts.length != 3) {
            throw new DigiPresBeException("OAuth state payload corrupt", 1214, 400);
        }
        long issuedAt;
        UUID tenantId;
        try {
            tenantId = UUID.fromString(parts[1]);
            issuedAt = Long.parseLong(parts[2]);
        } catch (IllegalArgumentException ex) {
            throw new DigiPresBeException("OAuth state payload unparseable", 1215, 400);
        }
        if (Instant.now().getEpochSecond() - issuedAt > MAX_AGE_SECONDS) {
            throw new DigiPresBeException("OAuth state expired", 1216, 400);
        }
        return tenantId;
    }

    private String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance(ALG);
            mac.init(jwtSigningKey);
            return base64Url(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new DigiPresBeException(ex, 1217, 500);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a.length() != b.length()) return false;
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
