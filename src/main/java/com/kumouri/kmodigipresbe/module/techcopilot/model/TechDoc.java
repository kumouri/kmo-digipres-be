package com.kumouri.kmodigipresbe.module.techcopilot.model;

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
 * Tech Copilot (T13) — one document in a tenant's field-tech corpus: an equipment manual, an SOP, or a
 * spec sheet. The {@link #text} is the grounding corpus.
 *
 * <p><strong>The headline net-new over the RE-1 concierge is chunking.</strong> A real-estate disclosure
 * was a short line → one embedding. A manual is multi-page → on create/update the {@code TechDocService}
 * splits {@link #text} into overlapping windows ({@code TechDocChunker}) and embeds <em>each chunk</em> as
 * the embedding source type {@code "TechDoc"} ({@code RagRetrievalService.TECH_DOC_SOURCE_TYPE}) carrying
 * {@code techDocId} + {@code chunkIndex} metadata — N vector upserts per doc. The core
 * {@code EmbeddingPipeline} is untouched (the {@code ListingDisclosureService} rationale —
 * {@code indexAttachment} embeds only filename+content-type, useless for grounding); the chunk vectors are
 * not separate Mongo documents, only entries in the shared {@code vector_index}.
 *
 * <p>{@link #indexedAt} is stamped after a successful chunk-and-embed; it stays {@code null} when embedding
 * is unavailable (best-effort — error {@code 4492}, a re-index can retry; the doc is never lost — the RE-1
 * {@code ListingDisclosure} posture). {@link #chunkCount} records how many chunks are currently indexed
 * (so a shrunk re-index can delete the stale trailing chunk vectors).
 *
 * <p>{@code TenantScoped} for isolation; not {@code Auditable} (a content row — the
 * {@code ListingDisclosure}/{@code Listing} rationale). Compound index {@code {tenantId, equipmentType}}.
 */
@Document("tech_docs")
@CompoundIndex(name = "tenant_equipment_idx", def = "{ 'tenantId': 1, 'equipmentType': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class TechDoc implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Human-friendly doc title (e.g. "Carrier 58STA Furnace — Installation & Service Manual"). */
    private String title;

    /** The coarse equipment category — a citation/UI label (never a retrieval restriction; T13-D3). */
    @Builder.Default
    private EquipmentType equipmentType = EquipmentType.GENERAL;

    /** Optional provenance note (manufacturer / model / SOP id / URL the doc came from). */
    private String source;

    /** The full document text — THE grounding corpus; chunked + embedded on save. */
    private String text;

    /** Number of chunks currently embedded for this doc; {@code 0} until indexed. */
    @Builder.Default
    private int chunkCount = 0;

    /** When the text was last chunked + embedded into the vector index; {@code null} if not yet indexed. */
    private Instant indexedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
