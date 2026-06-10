package com.kumouri.kmodigipresbe.module.realestate.listingprep.model;

import com.kumouri.kmodigipresbe.module.realestate.model.Listing;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;
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
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Real Estate Concierge (T10 — Listing Prep Studio) — one cohesive <strong>listing prep pack</strong> for a
 * {@link Listing}: a Fair-Housing-safe <strong>MLS description</strong> + a <strong>4-week dated social
 * calendar</strong> + an <strong>email campaign</strong>, parked in a <strong>draft → approve queue</strong>
 * (the RE-4 {@code ListingMarketingDraft} / GBP review-reply posture) so it is <strong>never
 * auto-published</strong>.
 *
 * <h2>How this differs from RE-4 ({@code ListingMarketingDraft})</h2>
 * RE-4's Marketing Studio already drafts an MLS description, ad-hoc per-channel social captions, and an email
 * blast (all Fair-Housing-linted, draft→approve). T10 reuses all of that and adds the genuine net-new: a
 * <strong>scheduled, dated</strong> {@link #socialCalendar} (a sequence of {@link SocialPost}s spread across
 * four weeks with real post dates and week indices — vs RE-4's single ad-hoc caption per channel), packaged
 * with the description + email into one prep artifact with one lifecycle. The per-photo {@link PhotoNote}s are
 * the <em>reused</em> RE-4 {@code AiVisionService.extract} vision callouts (feature/condition extraction is not
 * rebuilt).
 *
 * <h2>Fair-Housing guardrail (mandatory, two layers)</h2>
 * The generation system prompts forbid protected-class / steering language (layer 1); the deterministic
 * {@code FairHousingLint} runs over the description, every calendar post, and the email (layer 2). A flagged
 * <strong>calendar post</strong> is <strong>held + safe-substituted</strong> (its {@link SocialPost#copy} is
 * replaced with a vetted neutral template, {@link SocialPost#fairHousingSafe}=false, {@link #calendarHeldCount}
 * incremented) — a non-compliant post is <strong>never emitted</strong>. A flagged description / email is
 * surfaced on {@link #fairHousingFlags} for the agent to review (the RE-4 posture — the human approve is the
 * gate, never an auto-publish).
 *
 * <p>System ledger — {@code TenantScoped} for isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (an automation-owned draft, the
 * {@code ListingMarketingDraft} rationale). Compound index {@code {tenantId, listingId, createdAt desc}} for
 * the console list.
 */
@Document("re_listing_prep_packs")
@CompoundIndex(name = "tenant_listing_created_idx",
        def = "{ 'tenantId': 1, 'listingId': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ListingPrepPack implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The listing this prep pack is for. */
    private UUID listingId;

    /** The reused RE-4 MLS public remarks (the listing description). Blank if generation degraded. */
    private String mlsDescription;

    /** The reused RE-4 email-campaign body. Blank if generation degraded. */
    private String emailCampaign;

    /** The headline net-new — the dated 4-week social calendar (empty if generation degraded). */
    @Builder.Default
    private List<SocialPost> socialCalendar = List.of();

    /** Per-photo feature callouts read by {@code AiVisionService.extract} (reused RE-4 vision path; empty if no photos / all failed). */
    @Builder.Default
    private List<PhotoNote> photoCaptions = List.of();

    /** Deterministic Fair-Housing lint flags across the description, calendar posts, and email (empty = none). */
    @Builder.Default
    private List<FairHousingFlag> fairHousingFlags = List.of();

    /** Convenience: true iff {@link #fairHousingFlags} is non-empty (the agent sees "N phrases flagged"). */
    @Builder.Default
    private boolean fairHousingFlagged = false;

    /**
     * How many calendar posts were held + safe-substituted because the lint flagged their copy (a flagged
     * post is never emitted — its {@link SocialPost#copy} is the vetted neutral substitute). The headline
     * compliance signal for the calendar ("N posts auto-corrected for Fair Housing").
     */
    @Builder.Default
    private int calendarHeldCount = 0;

    /**
     * True iff generation degraded — a Claude budget/upstream/parse failure produced a partial/empty pack
     * (best-effort, T10 {@code 4463}); the pack is still saved DRAFTED for the agent (never an error, never
     * auto-published).
     */
    @Builder.Default
    private boolean generationDegraded = false;

    /** Lifecycle status — see {@link Status}. */
    @Builder.Default
    private Status status = Status.DRAFTED;

    /** When a staff member approved the pack (nullable until APPROVED). */
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
     * Pack lifecycle. {@code DRAFTED} → ({@code APPROVED} | {@code SKIPPED}). A pack is never auto-published;
     * {@code APPROVED} means copy-ready (paste-out — the actual MLS/social/email posting is out of scope, the
     * RE-4 approve→post posture); {@code SKIPPED} is terminal.
     */
    public enum Status { DRAFTED, APPROVED, SKIPPED }

    /**
     * One dated post in the 4-week social calendar (the T10 net-new). The {@link #postDate} is computed
     * deterministically by the orchestrator from the pack's start date + the model-returned
     * {@link #weekIndex}/{@link #dayOffset} (never model-hallucinated). {@link #fairHousingSafe} is false when
     * the lint flagged the originally-generated copy — in that case {@link #copy} is the vetted neutral
     * substitute and {@link #heldReason} explains why.
     */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SocialPost {
        /** The real calendar date this post is scheduled for (start-date + week/day offset). */
        private LocalDate postDate;
        /** Which of the four weeks this post falls in (1-4). */
        private int weekIndex;
        /** Days from the pack's start date (0-27), the source of {@link #postDate}. */
        private int dayOffset;
        /** The social channel for this post (INSTAGRAM / FACEBOOK / X). */
        private MarketingChannel channel;
        /** The post copy — the safe substitute when {@link #fairHousingSafe} is false. */
        private String copy;
        /** False iff the lint flagged the originally-generated copy (and {@link #copy} was substituted). */
        @Builder.Default
        private boolean fairHousingSafe = true;
        /** Why the post was held + substituted (the matched term), nullable when {@link #fairHousingSafe}. */
        private String heldReason;
    }

    /** One listing photo's reused RE-4 vision-read feature callout (woven into the copy + surfaced to the agent). */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PhotoNote {
        /** The {@code ListingPhoto} id this note came from. */
        private UUID photoId;
        /** The one-line caption the vision model produced (nullable if the read was blank). */
        private String caption;
        /** The discrete features the vision model called out (empty if none / the read was blank). */
        @Builder.Default
        private List<String> features = List.of();
    }

    /** One Fair-Housing lint hit: the banned term + the surface it appeared on + a short snippet of context. */
    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FairHousingFlag {
        /** The matched banned term/phrase (lower-cased as matched). */
        private String term;
        /** Which surface the term appeared in — "DESCRIPTION", "EMAIL", or a "CALENDAR week N" label. */
        private String surface;
        /** A short snippet of the surrounding copy so the agent can see the context. */
        private String snippet;
    }
}
