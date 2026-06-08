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
 * Real Estate Concierge (RE-1) — one typed disclosure line/section for a {@link Listing}.
 *
 * <p><strong>The {@link #text} is the grounding corpus.</strong> This is the RE-1 §3 critical decision:
 * {@code EmbeddingPipeline.indexAttachment} embeds only a file's <em>filename + content-type</em>, so a
 * disclosure PDF indexed that way would never ground a real question ("was the roof replaced?"). Instead,
 * {@code ListingDisclosureService} embeds <em>this text</em> directly as a new embedding source type
 * {@code "ListingDisclosure"} carrying {@code listingId} metadata (the module owns the upsert; the core
 * pipeline is untouched). Retrieval is listing-scoped via a {@code metadata.listingId} filter, so a
 * listing's answer can never bleed into another listing's disclosures or generic CRM vectors.
 *
 * <p>The raw uploaded document (if any) is still stored as an {@code Attachment} referenced by
 * {@link #sourceDocAttachmentId} so the agent can view/download the original — but the indexed corpus is
 * this typed text (plain-text/manual entry is the RE-1 floor; PDF text extraction is a fast-follow).
 *
 * <p>{@link #indexedAt} is stamped after a successful embed+upsert; it stays {@code null} when embedding
 * is unavailable (best-effort — error {@code 4252}, a re-index can retry; the disclosure is never lost).
 *
 * <p>{@code TenantScoped} for isolation; not {@code Auditable} (a content row, the {@code Listing}/
 * {@code WaitlistOffer} rationale). Compound index {@code {tenantId, listingId}}.
 */
@Document("re_listing_disclosures")
@CompoundIndex(name = "tenant_listing_idx", def = "{ 'tenantId': 1, 'listingId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ListingDisclosure implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The listing this disclosure belongs to — the retrieval scope key (stamped as vector metadata). */
    private UUID listingId;

    @Builder.Default
    private DisclosureType disclosureType = DisclosureType.GENERAL;

    /** The disclosure line/section — THE embedded grounding text. */
    private String text;

    /** Optional reference to the original uploaded document {@code Attachment} (for agent view/download). */
    private UUID sourceDocAttachmentId;

    /** When the text was last embedded + upserted into the vector index; {@code null} if not yet indexed. */
    private Instant indexedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
