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
 *
 * <p><strong>G.5 deep-link routing:</strong> an optional {@code redirectTo} value may be
 * supplied at request-time and is persisted on the {@link MagicLinkToken}. It is echoed
 * back in the {@link RedemptionResult} so the portal FE can route the authenticated
 * session to the intended page (e.g. {@code /portal/contracts/<id>}) without a second
 * round-trip. The echoed value is ALWAYS the one persisted at request-time — it is
 * NEVER read from the redeem request (open-redirect mitigation: §9 #5). When
 * {@code redirectTo} is {@code null} the behaviour is byte-identical to pre-G.5.
 *
 * <p><strong>Security fix BE-05:</strong> the emailed link base is no longer taken from
 * the request — {@link #buildLink} always uses the server-configured
 * {@code portalProperties.successRedirect()}. Previously a caller-supplied
 * {@code linkBaseUrl} let the real, branded sign-in email carry a live one-time token to
 * an attacker host (token exfiltration → portal account takeover). The {@code redirectTo}
 * deep-link handling above is unchanged.
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
     * The result of a successful magic-link redemption. Carries the resolved portal
     * {@link User} and the optional deep-link target that was persisted at request-time
     * (G.5 — nullable; null means no deep-link was supplied).
     */
    public record RedemptionResult(User user, String redirectTo) {}

    /**
     * Issues a magic-link token, persists its hash (and the optional {@code redirectTo}
     * deep-link target), and emails the raw token to the supplied email. Always returns
     * successfully whether or not we actually sent something — we don't leak which emails
     * exist as portal users.
     *
     * <p>Security fix BE-05: the emailed link base is ALWAYS derived from server config
     * ({@code portalProperties.successRedirect()}) — there is no caller-supplied
     * {@code linkBaseUrl}, so the branded sign-in email can never be steered to deliver a
     * live one-time token to an attacker-controlled host.
     *
     * @param redirectTo optional deep-link target to persist on the token so the FE can
     *                   route post-redeem; may be {@code null}
     */
    public Mono<Void> request(Tenant tenant, String email, String redirectTo) {
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
                .redirectTo(redirectTo)    // G.5: persisted at request-time; null → legacy
                .build();

        return tokens.save(row)
                .then(sendEmail(tenant, normalizedEmail, raw))
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
     * policy gate fires as the OAuth path. Returns a {@link RedemptionResult} carrying
     * the resolved portal user AND the {@code redirectTo} value persisted at request-time
     * (G.5 — may be null for tokens issued before G.5 or without a deep-link target).
     *
     * <p><strong>Open-redirect mitigation (§9 #5):</strong> the {@code redirectTo} in the
     * returned result is ALWAYS {@code token.getRedirectTo()} — the value stored when the
     * token was issued. It is NEVER read from the current redeem request.
     */
    public Mono<RedemptionResult> redeem(Tenant tenant, String rawToken) {
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
                                row.getEmail())
                        // Open-redirect mitigation (§9 #5): redirectTo is ALWAYS the value
                        // persisted at request-time (token.getRedirectTo()), NEVER from the
                        // redeem request.
                        .map(user -> new RedemptionResult(user, row.getRedirectTo())))
                .contextWrite(TenantContextHolder.write(
                        new TenantContext(tenant.getId(), null, Set.of())));
    }

    private Mono<Boolean> sendEmail(Tenant tenant, String email, String rawToken) {
        String link = buildLink(rawToken);
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

    /**
     * Builds the emailed sign-in link. Security fix BE-05: the base is ALWAYS the
     * server-configured {@code portalProperties.successRedirect()} — there is no
     * caller-supplied override — so the link can only ever point at the tenant's own
     * configured portal origin.
     */
    private String buildLink(String rawToken) {
        String base = portalProperties.successRedirect();
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
