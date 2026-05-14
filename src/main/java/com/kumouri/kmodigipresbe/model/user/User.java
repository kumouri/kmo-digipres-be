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
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

@Document("users")
@CompoundIndex(name = "tenant_email_idx", def = "{ 'tenantId': 1, 'email': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class User implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    @Indexed(unique = true)
    private String email;

    private String passwordHash;

    private String displayName;

    @Builder.Default
    private Set<String> roles = Set.of("STAFF");

    @Builder.Default
    private UserStatus status = UserStatus.ACTIVE;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum UserStatus { ACTIVE, DISABLED }
}
