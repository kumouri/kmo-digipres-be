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
 * A post-visit review-REQUEST (E3 Review Engine — review-request delivery). Created by
 * {@code ReviewRequestService} when a visit/job completes (a {@code BOOKING_COMPLETED} or
 * {@code MILESTONE_COMPLETED} domain event), then sent as a frictionless, no-incentive Google-review
 * SMS by the default-OFF {@code ReviewRequestSenderJob} once {@link #dueAt} passes.
 *
 * <h2>Generic attribution {@code (subjectType, subjectId)}</h2>
 * The request carries a vertical-agnostic attribution so the same engine serves salon (attribute to a
 * stylist — {@link ReviewSubjectType#STAFF} + the booking's {@code staffMemberId}) and home (attribute
 * to a job — {@link ReviewSubjectType#PROJECT} + the {@code projectId}) with no vertical code. The
 * insights rollup aggregates over this dimension.
 *
 * <h2>Creation idempotency</h2>
 * The unique {@code tenant_subject_contact_idx} on {@code (tenantId, subjectType, subjectId,
 * contactId)} makes "request a review from this contact for this subject" exactly-once:
 * {@code ReviewRequestService} probes it with an <strong>explicit boolean</strong>
 * ({@code findBy…(...).map(e->true).defaultIfEmpty(false)}) and inserts only if absent, with a
 * {@code DuplicateKeyException} backstop for the concurrent / re-emitted-completion case. A completed
 * booking fired twice (re-emit / restart) therefore yields exactly one PENDING request.
 *
 * <h2>Send idempotency</h2>
 * The sender claims a still-{@code PENDING} row atomically (flip {@code PENDING→SENT} + stamp
 * {@link #sentAt}) <strong>before</strong> the SMS, so a concurrent second sweep loses the claim and
 * sends zero duplicate. (See {@code ReviewRequestSenderJob}.)
 *
 * <p>System record — {@code TenantScoped} for tenant isolation but <strong>NOT
 * {@link com.kumouri.kmodigipresbe.audit.Auditable}</strong> (an automation-owned dedupe/state record,
 * the {@code GbpReviewReply} / {@code CoverageNudgeLog} rationale — never hand-curated as a CRM entity).
 */
@Document("review_requests")
@CompoundIndex(
        name = "tenant_subject_contact_idx",
        def = "{'tenantId':1,'subjectType':1,'subjectId':1,'contactId':1}",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ReviewRequest implements TenantScoped {

    @Id
    private UUID id;

    /** Stamped by {@code TenantStampingCallback}; part of the unique key. */
    private UUID tenantId;

    /** The attribution dimension (salon=STAFF, home=PROJECT); part of the unique key. */
    private ReviewSubjectType subjectType;

    /** The attributed entity id (a staffMemberId or projectId); part of the unique key. */
    private UUID subjectId;

    /** The contact the review request is sent to; part of the unique key. */
    private UUID contactId;

    /**
     * The completion event type that triggered this request ({@code booking.completed} /
     * {@code milestone.completed}) — provenance only.
     */
    private String sourceEventType;

    /**
     * The source entity reference (the bookingId / milestoneId) as a String — provenance only, not part
     * of the idempotency key (the {@code (subjectType, subjectId, contactId)} tuple is the dedupe key).
     */
    private String sourceRef;

    /** Lifecycle status — see {@link Status}. */
    @Builder.Default
    private Status status = Status.PENDING;

    /** When the request becomes eligible to send (completion instant + the configured delay). */
    private Instant dueAt;

    /** When the request SMS was sent (null until SENT) — also the per-contact frequency-cap anchor. */
    private Instant sentAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    /**
     * Review-request lifecycle. {@code PENDING} → ({@code SENT} | {@code SKIPPED} | {@code FAILED}).
     * {@code SENT} is terminal-happy (the ask went out); {@code SKIPPED} = a gate blocked it (opt-out /
     * no link / frequency cap) and it will not be retried for that contact+subject; {@code FAILED} is
     * reserved for a send that was claimed but errored (kept for observability — not auto-retried).
     */
    public enum Status { PENDING, SENT, SKIPPED, FAILED }
}
