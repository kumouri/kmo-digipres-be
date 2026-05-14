package com.kumouri.kmodigipresbe.model.auth;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * External identity (OAuth provider sub, magic-link identifier, or registered passkey)
 * bound to a portal {@link com.kumouri.kmodigipresbe.model.user.User}.
 * <p>One User may have many identities (Google + Microsoft + a passkey). The
 * {@code (tenantId, provider, providerSubject)} tuple is unique — a given Google
 * account can be linked to different Users across tenants, but only once per tenant.
 */
@Document("user_identities")
@CompoundIndex(
        name = "tenant_provider_subject_idx",
        def = "{ 'tenantId': 1, 'provider': 1, 'providerSubject': 1 }",
        unique = true)
@CompoundIndex(
        name = "tenant_user_provider_idx",
        def = "{ 'tenantId': 1, 'userId': 1, 'provider': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class UserIdentity implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID userId;

    private Provider provider;

    /**
     * Stable identifier from the provider (OIDC {@code sub}, magic-link email-hash,
     * or WebAuthn credential id in base64url).
     */
    private String providerSubject;

    private String email;

    @Builder.Default
    private boolean emailVerified = false;

    private String displayName;

    private Instant linkedAt;

    private Instant lastUsedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Provider { GOOGLE, MICROSOFT, MAGIC_LINK, PASSKEY }
}
