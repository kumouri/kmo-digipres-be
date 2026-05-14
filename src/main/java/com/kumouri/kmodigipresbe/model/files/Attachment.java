package com.kumouri.kmodigipresbe.model.files;

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
import java.util.UUID;

/**
 * Generic attachment metadata for any CRM entity. The bytes live in S3
 * (tenant-prefixed key under {@link #storageRef}); this record is just the
 * pointer + display data.
 *
 * <p>Phase 7's headline. Generalizes Phase 3's {@code Capture} which was
 * field-service-only — the file-storage primitive itself moved out of the
 * module to core (see {@code service/storage/FileStorageService}).
 */
@Document("attachments")
@CompoundIndex(name = "tenant_subject_idx",
        def = "{ 'tenantId': 1, 'subjectType': 1, 'subjectId': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Attachment implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /**
     * The entity this attachment hangs off — {@code "CONTACT"}, {@code "COMPANY"},
     * {@code "DEAL"}, {@code "QUOTE"}, {@code "INVOICE"}, etc. Free-form string so
     * modules can attach to their own types (e.g. {@code "WORK_ORDER"}).
     */
    private String subjectType;
    private UUID subjectId;

    private String filename;
    private String contentType;
    private Long sizeBytes;

    /**
     * S3 object key produced by {@code FileStorageService.presignUpload}. Must
     * start with the tenant prefix {@code tenants/<tenantId>/}.
     */
    private String storageRef;

    private UUID uploadedByUserId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
