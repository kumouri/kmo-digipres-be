package com.kumouri.kmodigipresbe.model.contact;

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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Document("companies")
@CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Company implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String website;
    private String industry;

    @Builder.Default
    private List<PostalAddress> addresses = List.of();

    @Builder.Default
    private Set<String> tags = Set.of();

    private UUID ownerId;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
