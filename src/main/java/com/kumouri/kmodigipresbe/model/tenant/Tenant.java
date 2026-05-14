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

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum TenantStatus { ACTIVE, SUSPENDED, ARCHIVED }
}
