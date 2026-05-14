package com.kumouri.kmodigipresbe.model.report;

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

/**
 * Ordered set of saved-report references with grid layout. The FE renders each
 * referenced {@link SavedReport} at the specified grid coordinates.
 */
@Document("dashboards")
@CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Dashboard implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;

    @Builder.Default
    private List<DashboardItem> items = List.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
