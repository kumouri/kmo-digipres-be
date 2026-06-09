package com.kumouri.kmodigipresbe.model.integration;

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
 * The Google-Business-Profile review-reply ledger + draft record (NMM GBP review-reply
 * automation). Doubles as the <strong>idempotency ledger</strong> (one row per
 * (tenant, GBP review id), the {@code CalComWebhookEvent} / {@code TwilioVoicemailEvent}
 * pattern) <em>and</em> the persisted draft Rob reviews/approves.
 *
 * <p>The unique {@code tenant_review_idx} on {@code (tenantId, reviewId)} is the
 * exactly-once guarantee: {@code GbpReviewPoller} inserts this row <strong>FIRST</strong>
 * (before drafting a reply or notifying) so a re-poll of the same review id hits a
 * {@code DuplicateKeyException} and records ZERO second effect (no duplicate draft, no
 * duplicate notify, no duplicate event).
 *
 * <p>System ledger — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (the same rationale as
 * {@code CalComWebhookEvent} / {@code TwilioVoicemailEvent} / {@code RecurringInvoiceOccurrence}:
 * an infrastructure dedupe record, not a hand-curated CRM entity). Unlike a pure webhook
 * ledger it also carries the review snapshot + the drafted reply + a small status machine
 * so the admin surface can list, edit, post or skip — but it is still owned end-to-end by
 * the automation, never edited as a CRM document.
 */
@Document("gbp_review_replies")
@CompoundIndex(
        name = "tenant_review_idx",
        def = "{'tenantId':1,'reviewId':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class GbpReviewReply implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The Google review id (the GBP {@code reviews/*} resource name); part of the unique key. */
    private String reviewId;

    /** Star rating 1..5 as reported by GBP (nullable if the payload omitted it). */
    private Integer rating;

    /** The reviewer's free-text comment (nullable — a star-only review carries no comment). */
    private String comment;

    /** The reviewer's display name (nullable). */
    private String reviewerName;

    /** When the review was created on Google (nullable). */
    private Instant reviewCreateTime;

    /** The on-brand reply drafted by {@code GbpReplyDraftService} (nullable if drafting failed). */
    private String draftedReply;

    /** Lifecycle status — see {@link Status}. */
    @Builder.Default
    private Status status = Status.DRAFTED;

    /** When the (optionally edited) reply was posted back to Google (nullable until POSTED). */
    private Instant postedAt;

    /**
     * The sentiment classification of this review (E3 Review Engine — sentiment triage). Additive
     * nullable: computed by {@code ReviewSentimentService} on ingest and stored here so the admin
     * list + the per-entity insights breakdown can read it. Legacy rows (ingested before E3)
     * deserialize {@code null} (the E-D8 additive-nullable precedent).
     */
    private ReviewSentiment sentiment;

    /**
     * How {@link #sentiment} was determined ({@code RATING} baseline or {@code AI}-refined). Additive
     * nullable; null on legacy rows and whenever {@link #sentiment} is null.
     */
    private SentimentSource sentimentSource;

    private Instant receivedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * Review-reply lifecycle. {@code DRAFTED} → ({@code POSTED} | {@code SKIPPED}). A reply is
     * never re-posted once {@code POSTED}; {@code SKIPPED} is terminal (Rob chose not to reply).
     */
    public enum Status { DRAFTED, POSTED, SKIPPED }
}
