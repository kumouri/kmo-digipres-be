package com.kumouri.kmodigipresbe.tenancy.permissions;

import com.kumouri.kmodigipresbe.audit.Auditable;
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
import java.util.List;
import java.util.UUID;

/**
 * One field-permission policy per tenant. Loaded by
 * {@code FieldPermissionRedactor} on every response; cached lookup is a future
 * optimisation if read latency matters.
 */
@Document("field_permission_policies")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FieldPermissionPolicy implements Auditable {

    @Id
    private UUID id;

    @Indexed(unique = true)
    private UUID tenantId;

    @Builder.Default
    private List<FieldPermissionRule> rules = List.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
