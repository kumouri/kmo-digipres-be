package com.kumouri.kmodigipresbe.module.homeservices.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
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
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

@Document("equipment")
@CompoundIndex(name = "tenant_jobsite_idx", def = "{ 'tenantId': 1, 'jobSiteId': 1 }")
@CompoundIndex(name = "tenant_warranty_idx", def = "{ 'tenantId': 1, 'warrantyExpiresAt': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Equipment implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID jobSiteId;

    private String equipmentType;
    private String manufacturer;
    private String model;
    private String serial;

    private LocalDate installDate;
    private Instant warrantyExpiresAt;
    private Instant lastServicedAt;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getEntityType() {
        return "EQUIPMENT";
    }
}
