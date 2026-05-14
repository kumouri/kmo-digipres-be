package com.kumouri.kmodigipresbe.model.auth;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Registered WebAuthn (FIDO2 / passkey) credential. The {@code credentialIdBase64Url}
 * is the assertion key; lookup by it (within a tenant) is the login pivot.
 */
@Document("webauthn_credentials")
@CompoundIndex(
        name = "tenant_credential_id_idx",
        def = "{ 'tenantId': 1, 'credentialIdBase64Url': 1 }",
        unique = true)
@CompoundIndex(
        name = "tenant_user_idx",
        def = "{ 'tenantId': 1, 'userId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WebAuthnCredential implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID userId;

    private String credentialIdBase64Url;

    /** COSE_Key-encoded public key bytes (webauthn4j-managed serialized form). */
    private byte[] attestedCredentialDataBytes;

    /** Authenticator attestation type (e.g. "none", "indirect", "direct"). */
    private String attestationType;

    @Builder.Default
    private long signCount = 0L;

    @Builder.Default
    private List<String> transports = List.of();

    /** User-supplied label for displaying the passkey in settings. */
    private String displayName;

    private Instant lastUsedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;
}
