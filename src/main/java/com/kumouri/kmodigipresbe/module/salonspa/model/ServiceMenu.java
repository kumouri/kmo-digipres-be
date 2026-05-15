package com.kumouri.kmodigipresbe.module.salonspa.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
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

@Document("salon_service_menus")
@CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ServiceMenu implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;

    @Builder.Default
    private List<ServiceMenuItem> services = List.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "SERVICE_MENU";
    }
}
