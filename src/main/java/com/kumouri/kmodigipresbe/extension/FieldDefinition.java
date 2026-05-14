package com.kumouri.kmodigipresbe.extension;

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
import java.util.List;
import java.util.UUID;

/**
 * Per-tenant metadata describing one custom field on one entity type. Values are
 * stored under {@code customFields[key]} on the target entity; validation is enforced
 * by {@link CustomFieldValidator} on every save.
 *
 * <p>The compound index on (tenantId, entityType, key) is unique — a tenant cannot
 * declare two definitions for the same (entity, key) pair.
 */
@Document("field_definitions")
@CompoundIndex(name = "tenant_entity_key_idx",
        def = "{ 'tenantId': 1, 'entityType': 1, 'key': 1 }",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FieldDefinition implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /**
     * The entity this field applies to. Values are the constants on
     * {@link EntityType} but stored as a free string so vertical modules can
     * register their own entity types without modifying core enums.
     */
    private String entityType;

    /**
     * The map key under {@code customFields} on the target entity. Must be a
     * stable identifier; the label is what's shown in the UI.
     */
    private String key;

    private String label;

    private FieldType type;

    @Builder.Default
    private boolean required = false;

    /**
     * Valid choices when {@code type == ENUM}. Null/empty for other types.
     */
    @Builder.Default
    private List<String> options = List.of();

    /**
     * For {@code type == LOOKUP}: the entity type the value points at
     * (e.g. {@code "CONTACT"}). The stored value is then a UUID id.
     */
    private String lookupTarget;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
