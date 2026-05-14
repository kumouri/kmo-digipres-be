package com.kumouri.kmodigipresbe.portal;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.auth.MagicLinkToken;
import com.kumouri.kmodigipresbe.model.auth.PortalInvitation;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.auth.MagicLinkTokenRepository;
import com.kumouri.kmodigipresbe.repository.auth.PortalInvitationRepository;
import com.kumouri.kmodigipresbe.service.portal.MagicLinkService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import reactor.test.StepVerifier;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Magic-link redemption end-to-end with a pre-staged token row. Bypasses the email
 * send (which would require live SMTP) and asserts the security-relevant parts:
 * expired tokens are rejected, used tokens cannot be re-used, and a successful
 * redemption routes through {@link MagicLinkService}'s call to
 * {@link com.kumouri.kmodigipresbe.service.portal.UserIdentityService#findOrProvision}
 * — i.e. the same signup policy gate fires as the OAuth path.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
class MagicLinkRedeemIT {

    @Autowired MagicLinkService magicLinkService;
    @Autowired TenantRepository tenants;
    @Autowired MagicLinkTokenRepository tokens;
    @Autowired PortalInvitationRepository invitations;

    private Tenant tenant;

    @BeforeEach
    void seed() {
        tenant = tenants.save(Tenant.builder()
                .id(UUID.randomUUID())
                .slug("magic-" + UUID.randomUUID())
                .displayName("Magic Link Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .clientSignupPolicy(Tenant.ClientSignupPolicy.INVITE_ONLY)
                .build()).block();
    }

    @Test
    void redeemValidTokenWithInvitationProvisionsUser() {
        // Pre-stage: invitation + magic-link token for the same email
        invitations.save(PortalInvitation.builder()
                        .id(UUID.randomUUID())
                        .email("alice@example.com")
                        .tokenHash("invitation-hash-" + UUID.randomUUID())
                        .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                        .status(PortalInvitation.Status.PENDING)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();

        String raw = randomToken();
        tokens.save(MagicLinkToken.builder()
                        .id(UUID.randomUUID())
                        .email("alice@example.com")
                        .tokenHash(sha256Hex(raw))
                        .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();

        var user = magicLinkService.redeem(tenant, raw).block();
        assertThat(user).isNotNull();
        assertThat(user.getEmail()).isEqualTo("alice@example.com");
        assertThat(user.getPortal()).isEqualTo(
                com.kumouri.kmodigipresbe.model.user.User.Portal.CLIENT);
    }

    @Test
    void expiredTokenIsRejected() {
        String raw = randomToken();
        tokens.save(MagicLinkToken.builder()
                        .id(UUID.randomUUID())
                        .email("alice@example.com")
                        .tokenHash(sha256Hex(raw))
                        .expiresAt(Instant.now().minus(Duration.ofMinutes(1)))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();

        StepVerifier.create(magicLinkService.redeem(tenant, raw))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getErrorCode() == 1244)
                .verify();
    }

    @Test
    void redeemingTwiceFails() {
        invitations.save(PortalInvitation.builder()
                        .id(UUID.randomUUID())
                        .email("bob@example.com")
                        .tokenHash("inv-bob")
                        .expiresAt(Instant.now().plus(Duration.ofHours(24)))
                        .status(PortalInvitation.Status.PENDING)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();

        String raw = randomToken();
        tokens.save(MagicLinkToken.builder()
                        .id(UUID.randomUUID())
                        .email("bob@example.com")
                        .tokenHash(sha256Hex(raw))
                        .expiresAt(Instant.now().plus(Duration.ofMinutes(10)))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx()))
                .block();

        magicLinkService.redeem(tenant, raw).block();

        StepVerifier.create(magicLinkService.redeem(tenant, raw))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getErrorCode() == 1243)
                .verify();
    }

    @Test
    void unknownTokenIsRejected() {
        StepVerifier.create(magicLinkService.redeem(tenant, randomToken()))
                .expectErrorMatches(ex -> ex instanceof DigiPresBeException
                        && ((DigiPresBeException) ex).getErrorCode() == 1242)
                .verify();
    }

    private TenantContext ctx() {
        return new TenantContext(tenant.getId(), null, Set.of());
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
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
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
