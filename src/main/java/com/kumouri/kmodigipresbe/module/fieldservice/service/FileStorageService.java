package com.kumouri.kmodigipresbe.module.fieldservice.service;

import java.time.Duration;
import java.util.UUID;

public interface FileStorageService {

    /**
     * Produce a presigned upload URL for the FE to PUT a capture directly. Backend
     * never sees the bytes. Returned key is what the FE later POSTs in
     * {@code /captures}.
     */
    Presigned presignUpload(UUID tenantId, UUID workOrderId, String contentType,
                            String suffix, Duration ttl);

    /**
     * Produce a presigned download URL for the FE to GET a capture.
     */
    String presignDownload(UUID tenantId, String storageRef, Duration ttl);

    record Presigned(String url, String storageRef, String method) {
    }
}
