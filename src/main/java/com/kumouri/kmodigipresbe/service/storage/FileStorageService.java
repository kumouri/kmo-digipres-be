package com.kumouri.kmodigipresbe.service.storage;

import reactor.core.publisher.Mono;

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
 *
 * <h2>Server-side byte store (Phase F — F-D8)</h2>
 * {@link #putBytes} is a narrowly-scoped addition for the signed-PDF-to-S3
 * integrity path: the Documenso webhook receives a signed legal document
 * server-side (the FE never sees those bytes) and must persist them intact.
 * The presign-only contract for all other paths is unchanged.
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

    /**
     * Server-side byte store (Phase F — F-D8). Stores {@code bytes} directly in
     * S3-compatible storage under {@code tenants/<tenantId>/<partition>/<uuid>.<suffix>}
     * and returns the storage ref (key). The caller is responsible for supplying
     * exactly the bytes to store — no transformation is applied (legal-integrity
     * invariant: the signed PDF is stored exactly as received from Documenso,
     * never re-rendered).
     *
     * <p>Returns a {@link Mono} that emits the storage ref on success or errors
     * with a {@link com.kumouri.kmodigipresbe.exceptions.DigiPresBeException}
     * ({@code 1310}/503) if the bucket is not configured.
     */
    Mono<String> putBytes(UUID tenantId, String partition, byte[] bytes,
                          String contentType, String suffix);

    /**
     * Server-side byte read (RE-4 — Marketing Studio). The read-twin of
     * {@link #putBytes}: fetches the object at {@code storageRef} directly into
     * memory using the non-blocking {@code S3AsyncClient} and returns its bytes.
     * Used when the server needs the actual bytes of an already-stored object —
     * the RE-4 Marketing Studio reads back a listing photo {@code Attachment} to
     * feed {@code AiVisionService.extract} for a feature caption.
     *
     * <p>Like {@link #presignDownload}, it refuses a key that is not under this
     * tenant's prefix ({@code tenants/<tenantId>/...}) — error
     * {@link com.kumouri.kmodigipresbe.exceptions.DigiPresBeException} {@code 1311}/403,
     * the cross-tenant guard — and errors {@code 1310}/503 if the bucket is not
     * configured. Returns a {@link Mono} that emits the bytes on success.
     */
    Mono<byte[]> getBytes(UUID tenantId, String storageRef);

    record Presigned(String url, String storageRef, String method) {
    }
}
