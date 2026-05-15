package com.kumouri.kmodigipresbe.model.sync;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document("tombstones")
@CompoundIndex(name = "tenant_collection_deletedAt_idx",
        def = "{'tenantId':1,'collection':1,'deletedAt':1}")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Tombstone implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String collection;

    private UUID deletedId;

    @Indexed(name = "tombstone_deletedAt_ttl_idx", expireAfter = "90d")
    private Instant deletedAt;
}
