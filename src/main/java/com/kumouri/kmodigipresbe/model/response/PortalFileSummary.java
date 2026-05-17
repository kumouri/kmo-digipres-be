package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.model.files.Attachment;

import java.time.Instant;

/**
 * Portal-facing file/attachment projection (Phase G — G-D1).
 *
 * <p>Omits {@code tenantId}, {@code version}, {@code subjectType}, {@code subjectId},
 * {@code storageRef} (raw S3 key), {@code uploadedByUserId}, and the {@code updatedAt}
 * timestamp — these are staff-internal. The raw {@code storageRef} is never exposed
 * to the portal; downloads are served via a presign endpoint gated by
 * {@code PortalOwnershipGuard} (G.4).
 *
 * <p>Note: {@code Attachment.sizeBytes} is the field name (not {@code size}) — the
 * portal exposes it as {@code sizeBytes} to avoid ambiguity with the Java record
 * accessor.
 */
public record PortalFileSummary(
        String id,
        String filename,
        String contentType,
        Long sizeBytes,
        Instant createdAt) {

    public static PortalFileSummary from(Attachment attachment) {
        return new PortalFileSummary(
                attachment.getId() == null ? null : attachment.getId().toString(),
                attachment.getFilename(),
                attachment.getContentType(),
                attachment.getSizeBytes(),
                attachment.getCreatedAt());
    }
}
