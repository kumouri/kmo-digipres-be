package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.config.PortalProperties;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.auth.MagicLinkToken;
import com.kumouri.kmodigipresbe.model.auth.UserIdentity;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.model.user.User;
import com.kumouri.kmodigipresbe.repository.auth.MagicLinkTokenRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * Magic-link sign-in: anonymous user supplies an email; if there's an existing portal
 * {@link User} for {@code (tenantId, email)} OR a pending invitation, we email them a
 * one-time link. Redemption mints the same portal JWT as OAuth and passkey paths.
 * <p>The raw token is only ever in the email body and the {@code /redeem} request.
 * Mongo stores only the SHA-256 hash.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MagicLinkService {

    private static final int TOKEN_BYTES = 32;

    private final MagicLinkTokenRepository tokens;
    private final UserIdentityService userIdentityService;
    private final EmailService emailService;
    private final PortalProperties portalProperties;

    @Value("${kmosf.mail.smtp.username:info@kmodigitalpresence.com}")
    private String fromAddress;

    /**
     * Issues a magic-link token, persists its hash, and emails the raw token to the
     * supplied email. Always returns successfully whether or not we actually sent
     * something — we don't leak which emails exist as portal users.
     */
    public Mono<Void> request(Tenant tenant, String email, String linkBaseUrl) {
        String normalizedEmail = email == null ? null : email.toLowerCase();
        if (normalizedEmail == null || normalizedEmail.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Email is required for magic-link sign-in", 1240, 400));
        }
        String raw = generateRawToken();
        String hash = sha256Hex(raw);
        Instant expires = Instant.now().plus(
                Duration.ofMinutes(portalProperties.magicLinkTtlMinutes()));

        MagicLinkToken row = MagicLinkToken.builder()
                .id(UUID.randomUUID())
                .email(normalizedEmail)
                .tokenHash(hash)
                .expiresAt(expires)
                .build();

        return tokens.save(row)
                .then(sendEmail(tenant, normalizedEmail, raw, linkBaseUrl))
                .onErrorResume(ex -> {
                    log.warn("Magic-link issuance failed for {} on tenant {}",
                            normalizedEmail, tenant.getSlug(), ex);
                    return Mono.empty();
                })
                .then();
    }

    /**
     * Redeems a magic-link token: looks up by hash, verifies not-expired and not-used,
     * routes through {@link UserIdentityService#findOrProvision} so the same signup
     * policy gate fires as the OAuth path. Returns the resolved portal user.
     */
    public Mono<User> redeem(Tenant tenant, String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Magic-link token is required", 1241, 400));
        }
        String hash = sha256Hex(rawToken);
        return tokens.findByTenantIdAndTokenHash(tenant.getId(), hash)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Magic-link token is invalid or expired", 1242, 401)))
                .flatMap(row -> {
                    if (row.getRedeemedAt() != null) {
                        return Mono.error(new DigiPresBeException(
                                "Magic-link token already redeemed", 1243, 401));
                    }
                    if (row.getExpiresAt() != null
                            && row.getExpiresAt().isBefore(Instant.now())) {
                        return Mono.error(new DigiPresBeException(
                                "Magic-link token expired", 1244, 401));
                    }
                    row.setRedeemedAt(Instant.now());
                    return tokens.save(row).thenReturn(row);
                })
                .flatMap(row -> userIdentityService.findOrProvision(
                        tenant,
                        UserIdentity.Provider.MAGIC_LINK,
                        row.getEmail(),  // sub == email for magic-link
                        row.getEmail(),
                        true,  // email is implicitly verified by the receive-link round-trip
                        row.getEmail()))
                .contextWrite(TenantContextHolder.write(
                        new TenantContext(tenant.getId(), null, Set.of())));
    }

    private Mono<Boolean> sendEmail(Tenant tenant, String email, String rawToken,
                                    String linkBaseUrl) {
        String link = buildLink(linkBaseUrl, rawToken);
        String body = """
                <p>Hi,</p>
                <p>Sign in to %s with this one-time link (good for %d minutes):</p>
                <p><a href="%s">%s</a></p>
                <p>If you didn't request this, you can ignore this email.</p>
                """.formatted(
                        tenant.getDisplayName(),
                        portalProperties.magicLinkTtlMinutes(),
                        link,
                        link);
        SingleEmailCommunicationRequest req = SingleEmailCommunicationRequest.builder()
                .from(new EmailContact(fromAddress))
                .to(new EmailContact(email))
                .subject("Sign in to " + tenant.getDisplayName())
                .body(body)
                .build();
        return emailService.sendSingleEmail(req);
    }

    private String buildLink(String linkBaseUrl, String rawToken) {
        String base = linkBaseUrl == null || linkBaseUrl.isBlank()
                ? portalProperties.successRedirect()
                : linkBaseUrl;
        String separator = base.contains("?") ? "&" : "?";
        return base + separator + "magic_token=" + rawToken;
    }

    private static String generateRawToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hashed = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hashed.length * 2);
            for (byte b : hashed) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new DigiPresBeException(ex, 1245, 500);
        }
    }
}
