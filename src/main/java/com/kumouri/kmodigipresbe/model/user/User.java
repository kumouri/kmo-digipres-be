package com.kumouri.kmodigipresbe.model.user;

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

@Document("users")
@CompoundIndex(name = "tenant_email_idx", def = "{ 'tenantId': 1, 'email': 1 }", unique = true)
// Email is globally unique among STAFF users (one human, one staff account). Portal
// CLIENT users are intentionally exempt so the same person can be a client of multiple
// tenants under their personal email. Without the partial filter, the global unique
// index would force every cross-tenant client to use distinct email addresses.
@CompoundIndex(
        name = "staff_email_unique_idx",
        def = "{ 'email': 1 }",
        unique = true,
        partialFilter = "{ 'portal': 'STAFF' }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class User implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String email;

    // Nullable: portal/CLIENT users authenticate via OAuth, magic-link, or passkey
    // and may never set a password.
    private String passwordHash;

    private String displayName;

    @Builder.Default
    private Set<String> roles = Set.of("STAFF");

    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Builder.Default
    private Portal portal = Portal.STAFF;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum UserStatus { ACTIVE, INVITED, DISABLED }

    public enum Portal { STAFF, CLIENT }
}
