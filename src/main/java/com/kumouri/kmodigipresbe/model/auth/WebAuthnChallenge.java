package com.kumouri.kmodigipresbe.model.auth;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * In-flight WebAuthn challenge for either registration or login. The challenge bytes
 * are bound to the user (or, for usernameless login, to a session ticket) and expire
 * via a TTL index on {@code expiresAt}.
 */
@Document("webauthn_challenges")
@CompoundIndex(
        name = "tenant_ticket_idx",
        def = "{ 'tenantId': 1, 'ticket': 1 }",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WebAuthnChallenge implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID userId;

    /** Opaque short-lived correlator returned on /start and required on /finish. */
    private String ticket;

    private byte[] challengeBytes;

    private Purpose purpose;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    @CreatedDate
    private Instant createdAt;

    public enum Purpose { REGISTRATION, ASSERTION }
}
