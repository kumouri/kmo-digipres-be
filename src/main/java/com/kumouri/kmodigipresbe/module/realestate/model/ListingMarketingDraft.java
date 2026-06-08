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
import java.util.List;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-4 — Marketing Studio) — one Claude-drafted marketing package for a
 * {@link Listing}, parked in a <strong>draft→approve queue</strong> (the GBP {@code GbpReviewReply}
 * posture) so it is <strong>never auto-published</strong>.
 *
 * <p>A single agent "generate marketing for this listing" call (the {@code ListingMarketingService})
 * produces one of these in status {@link Status#DRAFTED}: Sonnet-drafted MLS remarks + N platform-tuned
 * social captions + an email blast (one {@link GeneratedPiece} per {@link MarketingChannel}), grounded in
 * the listing facts and the per-photo {@link PhotoCaption}s that {@code AiVisionService.extract} read from
 * the listing photos. A staff <em>approve</em> flips it to {@link Status#APPROVED} (copy-ready —
 * paste-out; the actual MLS/social posting is out of scope, the GBP approve→post posture). A staff
 * <em>skip</em> discards it ({@link Status#SKIPPED}).
 *
 * <p><strong>Fair-Housing guardrail (two layers, RE-4 §5 decision 5).</strong> The generation system
 * prompt forbids protected-class / steering language; in addition a deterministic post-generation lint
 * ({@code FairHousingLint}) flags residual risk terms across the generated pieces. The flags are surfaced
 * on {@link #fairHousingFlags} (and visible to the agent) — they do <strong>not</strong> block the draft
 * from human review (the agent decides). {@link #fairHousingFlagged} is a convenience boolean.
 *
 * <p>System ledger — {@code TenantScoped} for isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (an automation-owned draft, the
 * {@code GbpReviewReply} rationale). Compound index {@code {tenantId, listingId, createdAt desc}} for the
 * console list.
 */
@Document("re_listing_marketing_drafts")
@CompoundIndex(name = "tenant_listing_created_idx",
        def = "{ 'tenantId': 1, 'listingId': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ListingMarketingDraft implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The listing this marketing package is for. */
    private UUID listingId;

    /** The generated pieces — one per {@link MarketingChannel} (MLS remarks + social captions + email). */
    @Builder.Default
    private List<GeneratedPiece> pieces = List.of();

    /** Per-photo feature callouts read by {@code AiVisionService.extract} (empty if no photos / all failed). */
    @Builder.Default
    private List<PhotoCaption> photoCaptions = List.of();

    /** Deterministic Fair-Housing lint flags across the generated copy (empty = none surfaced). */
    @Builder.Default
    private List<FairHousingFlag> fairHousingFlags = List.of();

    /** Convenience: true iff {@link #fairHousingFlags} is non-empty (the agent sees "N phrases flagged"). */
    @Builder.Default
    private boolean fairHousingFlagged = false;

    /**
     * True iff generation degraded — a Claude budget/upstream/parse failure produced a partial/empty
     * package (best-effort, RE-4 {@code 4268}); the draft is still saved DRAFTED for the agent (never
     * an error, never auto-published).
     */
    @Builder.Default
    private boolean generationDegraded = false;

    /** Lifecycle status — see {@link Status}. */
    @Builder.Default
    private Status status = Status.DRAFTED;

    /** When a staff member approved the draft (nullable until APPROVED). */
    private Instant approvedAt;

    /** The staff user who approved (nullable until APPROVED). */
    private UUID approvedByUserId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * Draft lifecycle. {@code DRAFTED} → ({@code APPROVED} | {@code SKIPPED}). A draft is never
     * auto-published; {@code APPROVED} means copy-ready (paste-out); {@code SKIPPED} is terminal.
     */
    public enum Status { DRAFTED, APPROVED, SKIPPED }

    /** One generated marketing piece for a {@link MarketingChannel}. */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class GeneratedPiece {
        private MarketingChannel channel;
        /** The drafted copy for this channel (may be blank if generation degraded for this piece). */
        private String text;
    }

    /** One listing photo's vision-read feature callout (woven into the copy + surfaced to the agent). */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhotoCaption {
        /** The {@link ListingPhoto} id this caption came from. */
        private UUID photoId;
        /** The one-line caption the vision model produced (nullable if the read was blank). */
        private String caption;
        /** The discrete features the vision model called out (empty if none / the read was blank). */
        @Builder.Default
        private List<String> features = List.of();
    }

    /** One Fair-Housing lint hit: the banned term + the channel + a short snippet of context. */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FairHousingFlag {
        /** The matched banned term/phrase (lower-cased as matched). */
        private String term;
        /** The channel whose copy the term appeared in. */
        private MarketingChannel channel;
        /** A short snippet of the surrounding copy so the agent can see the context. */
        private String snippet;
    }
}
