package com.kumouri.kmodigipresbe.module.fieldservice.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import com.kumouri.kmodigipresbe.model.contact.PostalAddress;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexType;
import org.springframework.data.mongodb.core.index.GeoSpatialIndexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Document("job_sites")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class JobSite implements Auditable, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID contactId;
    private UUID companyId;

    private PostalAddress address;

    /**
     * GeoJSON point. Indexed with {@code 2dsphere} so {@code $near} queries work.
     */
    @GeoSpatialIndexed(name = "job_site_geo_idx", type = GeoSpatialIndexType.GEO_2DSPHERE)
    private LatLng location;

    private String label;
    private String accessNotes;

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
        return "JOB_SITE";
    }
}
