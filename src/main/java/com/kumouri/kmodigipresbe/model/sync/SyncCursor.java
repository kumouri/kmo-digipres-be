package com.kumouri.kmodigipresbe.model.sync;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document("sync_cursors")
@CompoundIndex(name = "tenant_user_collection_uidx",
        def = "{'tenantId':1,'userId':1,'collection':1}", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SyncCursor implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID userId;

    private String collection;

    private Instant cursor;

    @LastModifiedDate
    private Instant updatedAt;
}
