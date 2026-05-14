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
import java.util.Set;
import java.util.UUID;

/**
 * Invitation to a client portal user. {@link UserIdentityService} enforces this
 * when {@link com.kumouri.kmodigipresbe.model.tenant.Tenant.ClientSignupPolicy#INVITE_ONLY}
 * is the active policy for the tenant.
 * <p>{@link #tokenHash} stores a SHA-256 hash of the issued token; raw tokens are
 * shown to the user once at issuance.
 */
@Document("portal_invitations")
@CompoundIndex(
        name = "tenant_token_hash_idx",
        def = "{ 'tenantId': 1, 'tokenHash': 1 }",
        unique = true)
@CompoundIndex(
        name = "tenant_email_status_idx",
        def = "{ 'tenantId': 1, 'email': 1, 'status': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PortalInvitation implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String email;

    @Builder.Default
    private Set<String> roles = Set.of("CLIENT");

    private String tokenHash;

    private Instant expiresAt;

    @Builder.Default
    private Status status = Status.PENDING;

    private UUID invitedByUserId;

    private Instant redeemedAt;

    private UUID redeemedAsUserId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { PENDING, REDEEMED, EXPIRED, REVOKED }
}
