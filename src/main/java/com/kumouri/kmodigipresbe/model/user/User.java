package com.kumouri.kmodigipresbe.model.user;

import com.kumouri.kmodigipresbe.audit.Auditable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Transient;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
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
public class User implements Auditable {

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

    // Links a portal CLIENT user to the Contact record they own in the same tenant.
    // Null for STAFF users and for CLIENT users not yet stamped with a Contact link.
    // No index: we always traverse user→contact, never the reverse.
    private UUID contactId;

    /**
     * (Phase J) Nullable default client bill rate for this user, applied when a
     * {@code ProjectAssignment} carries no {@code billRateOverride} (rate-resolution
     * order). Hourly amount.
     */
    private BigDecimal defaultBillRate;

    /**
     * (Phase J) Nullable default contractor cost/pay rate, applied when a
     * {@code ProjectAssignment} carries no {@code costRateOverride}. Hourly amount;
     * drives the {@code TimeEntry.costRateAmount} stamp and the payout rollup.
     */
    private BigDecimal defaultCostRate;

    // Non-persisted projection of the owning Tenant's displayName, populated on
    // /auth/me (and mirrored on the login response) so the FE can show the
    // human business name instead of a raw tenant UUID. Spring Data @Transient
    // keeps it out of Mongo; Jackson still serializes it. Nullable.
    @Transient
    private String tenantName;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum UserStatus { ACTIVE, INVITED, DISABLED }

    public enum Portal { STAFF, CLIENT }
}
