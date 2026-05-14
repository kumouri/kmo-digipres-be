package com.kumouri.kmodigipresbe.module.fieldservice.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

@Document("captures")
@CompoundIndex(name = "tenant_workorder_idx",
        def = "{ 'tenantId': 1, 'workOrderId': 1, 'capturedAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Capture implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID workOrderId;

    private CaptureType type;

    /**
     * Opaque storage reference returned by {@code FileStorageService.presignUpload()}
     * — typically an S3 object key like
     * {@code tenants/<tenantId>/work-orders/<workOrderId>/<random>.jpg}.
     */
    private String storageRef;

    private String contentType;
    private Long sizeBytes;

    private Instant capturedAt;
    private UUID capturedByUserId;

    /**
     * Lat/lng pulled from EXIF metadata if available — useful for photo proof.
     * Stored as GeoJSON Point; no spatial index here (queries on captures don't
     * include geo predicates yet).
     */
    private LatLng exifLocation;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;
}
