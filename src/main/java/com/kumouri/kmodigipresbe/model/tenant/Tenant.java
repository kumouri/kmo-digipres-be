package com.kumouri.kmodigipresbe.model.tenant;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Document("tenants")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Tenant {
    @Id
    private UUID id;

    @Indexed(unique = true)
    private String slug;

    private String displayName;

    @Builder.Default
    private Set<String> enabledModules = Set.of();

    @Builder.Default
    private TenantStatus status = TenantStatus.ACTIVE;

    // Client-portal sign-up policy. INVITE_ONLY is the safe default — a brand-new
    // OAuth sign-in is rejected unless there is a matching PortalInvitation.
    @Builder.Default
    private ClientSignupPolicy clientSignupPolicy = ClientSignupPolicy.INVITE_ONLY;

    // When clientSignupPolicy = OPEN_DOMAIN, the email's domain must match one
    // of these (lowercased). Ignored for INVITE_ONLY and OPEN.
    @Builder.Default
    private Set<String> allowedSignupDomains = Set.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum TenantStatus { ACTIVE, SUSPENDED, ARCHIVED }

    public enum ClientSignupPolicy { INVITE_ONLY, OPEN_DOMAIN, OPEN }
}
