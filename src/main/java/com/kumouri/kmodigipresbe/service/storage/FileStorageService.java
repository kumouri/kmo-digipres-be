package com.kumouri.kmodigipresbe.service.storage;

import java.time.Duration;
import java.util.UUID;

/**
 * Tenant-prefixed S3-compatible object storage. The CRM never sees the bytes —
 * it hands out short-lived presigned URLs that the FE PUTs / GETs directly. All
 * keys live under {@code tenants/<tenantId>/...} so a misconfigured bucket
 * policy still keeps tenants apart at the key level.
 *
 * <p>Used by every subsystem that needs to upload binary files — field-service
 * captures (Phase 3), quote PDFs (Phase 7), generic attachments (Phase 7), and
 * any later module.
 */
public interface FileStorageService {

    /**
     * Produce a presigned PUT URL for a new object. {@code partition} is a
     * caller-chosen path segment (e.g. {@code "work-orders/<id>"}, {@code "quotes/<id>"},
     * {@code "attachments/<subjectType>/<id>"}) so each subsystem can keep its
     * own namespace under the tenant root.
     */
    Presigned presignUpload(UUID tenantId, String partition, String contentType,
                            String suffix, Duration ttl);

    /**
     * Produce a presigned GET URL for an existing object. Rejects keys that
     * don't start with this tenant's prefix.
     */
    String presignDownload(UUID tenantId, String storageRef, Duration ttl);

    record Presigned(String url, String storageRef, String method) {
    }
}
