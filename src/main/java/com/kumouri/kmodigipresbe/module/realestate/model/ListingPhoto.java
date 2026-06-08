package com.kumouri.kmodigipresbe.module.realestate.model;

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
 * Real Estate Concierge (RE-4 — Marketing Studio) — a photo an agent has uploaded for a {@link Listing}.
 *
 * <p>The bytes live in S3 (the shared {@code FileStorageService} {@code putBytes} path, the
 * {@code EquipmentVisionService} precedent); this row is the per-listing pointer. It also carries a
 * generic {@code Attachment} ({@code subjectType="LISTING"}, {@code subjectId=listingId}) so the photo
 * shows up on the standard attachment surface — {@link #attachmentId} links the two. The
 * {@code ListingMarketingService} reads the bytes back ({@code FileStorageService.getBytes}) at generate
 * time and runs {@code AiVisionService.extract} over each to weave per-photo feature callouts into the
 * marketing copy.
 *
 * <p>{@code TenantScoped} for isolation; not {@code Auditable} (a content row, the {@code Listing} /
 * {@code ListingDisclosure} rationale). Compound index {@code {tenantId, listingId}}.
 */
@Document("re_listing_photos")
@CompoundIndex(name = "tenant_listing_idx", def = "{ 'tenantId': 1, 'listingId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ListingPhoto implements TenantScoped {

    /** The {@code Attachment.subjectType} a listing photo is bound to. */
    public static final String SUBJECT_TYPE = "LISTING";

    @Id
    private UUID id;

    private UUID tenantId;

    /** The listing this photo belongs to. */
    private UUID listingId;

    /** The generic {@code Attachment} row (subjectType="LISTING") this photo also registers as. */
    private UUID attachmentId;

    /** S3 object key (tenant-prefixed) the bytes were stored under (the {@code putBytes} ref). */
    private String storageRef;

    private String filename;
    private String contentType;
    private Long sizeBytes;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
