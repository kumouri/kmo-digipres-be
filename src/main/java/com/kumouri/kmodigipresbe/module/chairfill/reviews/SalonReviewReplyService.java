package com.kumouri.kmodigipresbe.module.chairfill.reviews;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.gbp.GbpReplyDraftService;
import com.kumouri.kmodigipresbe.integration.gbp.GbpReplyDraftService.ReplyExemplar;
import com.kumouri.kmodigipresbe.integration.gbp.GbpReview;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * ChairFill CF-4 — drafts an on-brand, salon-voiced reply to a customer review and parks it in the
 * <strong>same {@code GbpReviewReply} approval queue NMM uses</strong> (DRAFTED → approve/skip via
 * the unchanged {@code GbpReviewReplyAdminService}/{@code GbpReviewReplyAdminController}). It is the
 * brain behind the demo <strong>paste-in</strong> path ({@code SalonReviewReplyController}) — no
 * live Google OAuth needed — and the salon generalization of the shipped GBP review-reply spine per
 * plan D4.
 *
 * <h2>Generalize, don't fork (D4)</h2>
 * The transport, budget gate, parse, cost, and error codes are the unchanged
 * {@link GbpReplyDraftService} — CF-4 calls its additive
 * {@link GbpReplyDraftService#draftReply(GbpReview, String, java.util.List) draftReply(review,
 * systemPromptOverride, exemplars)} overload with (a) a salon <strong>brand-tone system prompt</strong>
 * and (b) a few <strong>RAG-retrieved exemplar past approved replies</strong>
 * ({@link ReplyExemplarSource}). NMM/GBP tenants never get the salon prompt or exemplars — their
 * single-arg {@code draftReply(review)} call is byte-equivalent. The poller, ledger, and admin queue
 * are reused verbatim.
 *
 * <h2>Never auto-post (hard gate)</h2>
 * The drafted reply is persisted {@code DRAFTED} into the queue and an event is emitted. Posting (or
 * marking copy-ready) only happens through staff approval on the existing admin surface — this
 * service never calls {@code GbpApiClient.postReply}.
 *
 * <h2>Best-effort drafting</h2>
 * Both the exemplar retrieval and the Claude call are best-effort: an exemplar-source failure → no
 * exemplars; a Claude failure (budget {@code 1200}, upstream {@code 1202}, missing-key {@code 1203})
 * → a sensible <strong>generic on-brand fallback draft</strong> (never a blank, never a thrown
 * error, never a dropped review). The row is always ledgered so the reviewer surfaces in the queue.
 *
 * <h2>Idempotency</h2>
 * Like the poller, the review-id is the per-tenant idempotency key (the unique
 * {@code tenant_review_idx}): a re-submit of the same {@code reviewId} returns the existing row
 * (no duplicate draft / event). A paste-in submission with no caller-supplied id is given a
 * synthetic {@code pasted/<uuid>} id so each manual paste is distinct.
 *
 * <h2>Error codes</h2>
 * CF-4's band is {@code 4240-4244}: {@code 4240} is reserved for a malformed paste-in submission
 * (e.g. blank review text — surfaced by the controller). The AI codes {@code 1200-1203} and the
 * admin-queue codes {@code 4032/4033} are reused unchanged.
 */
@Slf4j
public class SalonReviewReplyService {

    /** Default count of past-approved-reply exemplars to ground a draft in (RAG, D4). */
    private static final int DEFAULT_EXEMPLAR_LIMIT = 3;

    private final GbpReplyDraftService draftService;
    private final ReplyExemplarSource exemplarSource;
    private final GbpReviewReplyRepository reviewReplies;
    private final DomainEventPublisher events;
    private final String brandTonePrompt;
    private final boolean exemplarsEnabled;
    private final int exemplarLimit;

    public SalonReviewReplyService(
            GbpReplyDraftService draftService,
            ReplyExemplarSource exemplarSource,
            GbpReviewReplyRepository reviewReplies,
            DomainEventPublisher events,
            String brandTonePrompt,
            boolean exemplarsEnabled,
            int exemplarLimit) {
        this.draftService = draftService;
        this.exemplarSource = exemplarSource;
        this.reviewReplies = reviewReplies;
        this.events = events;
        this.brandTonePrompt = brandTonePrompt == null ? "" : brandTonePrompt;
        this.exemplarsEnabled = exemplarsEnabled;
        this.exemplarLimit = exemplarLimit > 0 ? exemplarLimit : DEFAULT_EXEMPLAR_LIMIT;
    }

    /**
     * A pasted-in review to draft a salon-voiced reply for (the demo path). All fields but the
     * review text are optional. A {@code null}/blank {@code externalReviewId} mints a synthetic
     * {@code pasted/<uuid>} id so each manual paste is a distinct queue row.
     *
     * @param externalReviewId an optional stable id (e.g. a real GBP review id if known); nullable
     * @param rating           the review's star rating 1..5 (nullable)
     * @param comment          the review's text (required — blank is a {@code 4240} at the controller)
     * @param reviewerName     the reviewer's display name (nullable)
     * @param createTime       when the review was left (nullable; defaults to now)
     */
    public record PasteInReview(
            String externalReviewId,
            Integer rating,
            String comment,
            String reviewerName,
            Instant createTime) {
    }

    /**
     * Drafts an on-brand salon reply for a pasted-in review and parks it DRAFTED in the approval
     * queue. Idempotent on the (synthetic-or-supplied) review id. Best-effort drafting — always
     * ledgers the row; a Claude/exemplar failure degrades to a generic on-brand draft.
     */
    public Mono<GbpReviewReply> draftPasteIn(PasteInReview paste) {
        return TenantContextHolder.required()
                .flatMap(ctx -> draftForTenant(ctx.tenantId(), paste));
    }

    private Mono<GbpReviewReply> draftForTenant(UUID tenantId, PasteInReview paste) {
        String reviewId = (paste.externalReviewId() != null && !paste.externalReviewId().isBlank())
                ? paste.externalReviewId().trim()
                : "pasted/" + UUID.randomUUID();
        GbpReview review = new GbpReview(
                reviewId, paste.rating(), paste.comment(), paste.reviewerName(),
                paste.createTime() != null ? paste.createTime() : Instant.now());

        // Idempotency: a re-submit of the same review id returns the existing row (no duplicate).
        return reviewReplies.findByTenantIdAndReviewId(tenantId, reviewId)
                .flatMap(existing -> {
                    log.debug("CF-4 review {} already ledgered for tenant {} — returning existing "
                            + "draft (no duplicate)", reviewId, tenantId);
                    return Mono.just(existing);
                })
                .switchIfEmpty(Mono.defer(() -> insertThenDraft(tenantId, review)));
    }

    /** Ledger-insert-FIRST (the unique-index exactly-once backstop), then draft best-effort. */
    private Mono<GbpReviewReply> insertThenDraft(UUID tenantId, GbpReview review) {
        GbpReviewReply row = GbpReviewReply.builder()
                .tenantId(tenantId)
                .reviewId(review.reviewId())
                .rating(review.rating())
                .comment(review.comment())
                .reviewerName(review.reviewerName())
                .reviewCreateTime(review.createTime())
                .status(GbpReviewReply.Status.DRAFTED)
                .receivedAt(Instant.now())
                .build();
        return reviewReplies.save(row)
                .onErrorResume(DuplicateKeyException.class, e ->
                        // A concurrent submit of the same id lost the insert — return the winner's row.
                        reviewReplies.findByTenantIdAndReviewId(tenantId, review.reviewId()))
                .flatMap(saved -> draftAndPersist(tenantId, review, saved));
    }

    private Mono<GbpReviewReply> draftAndPersist(UUID tenantId, GbpReview review,
                                                 GbpReviewReply saved) {
        // If we returned a pre-existing/concurrent row that already has a draft, don't redraft.
        if (saved.getDraftedReply() != null && !saved.getDraftedReply().isBlank()) {
            return Mono.just(saved);
        }
        return retrieveExemplars(tenantId, review.rating())
                .flatMap(exemplars -> draftService
                        .draftReply(review, brandSystemPrompt(), exemplars)
                        .onErrorResume(e -> {
                            log.warn("CF-4 salon reply-draft failed for review {} (best-effort; "
                                    + "falling back to a generic on-brand draft): {}",
                                    review.reviewId(), e.getMessage());
                            return Mono.just(genericFallbackReply(review));
                        }))
                .map(draft -> (draft == null || draft.isBlank())
                        ? genericFallbackReply(review) : draft)
                .flatMap(draft -> {
                    saved.setDraftedReply(draft);
                    return reviewReplies.save(saved);
                })
                .doOnNext(persisted -> emitDrafted(tenantId, persisted));
    }

    private Mono<List<ReplyExemplar>> retrieveExemplars(UUID tenantId, Integer rating) {
        if (!exemplarsEnabled) {
            return Mono.just(List.of());
        }
        // The source is best-effort by contract; this defends even against an impl that throws.
        return exemplarSource.retrieve(tenantId, rating, exemplarLimit)
                .onErrorResume(e -> {
                    log.warn("CF-4 exemplar retrieval threw for tenant {} (degrading to none): {}",
                            tenantId, e.getMessage());
                    return Mono.just(List.of());
                });
    }

    /** The salon brand-tone system prompt (config-driven). Blank → the drafter's built-in default. */
    private String brandSystemPrompt() {
        if (brandTonePrompt.isBlank()) {
            return SALON_DEFAULT_SYSTEM_PROMPT;
        }
        return SALON_DEFAULT_SYSTEM_PROMPT + " " + brandTonePrompt.trim();
    }

    /**
     * A non-AI fallback reply used when Claude is unavailable/over-budget — short, gracious, and
     * sentiment-aware so a budget-exhausted salon still gets a usable, never-blank draft in the
     * queue to edit before approving (plan §4 AI-budget mitigation; never auto-posted).
     */
    private static String genericFallbackReply(GbpReview review) {
        String name = (review.reviewerName() != null && !review.reviewerName().isBlank())
                ? review.reviewerName().trim() : null;
        boolean critical = review.rating() != null && review.rating() <= 3;
        StringBuilder sb = new StringBuilder();
        if (critical) {
            sb.append(name != null ? ("Hi " + name + ", thank you for the feedback — ") : "Thank you for the feedback — ");
            sb.append("we're sorry your visit fell short of what we aim for. We'd love the chance "
                    + "to make it right; please reach out to us directly so we can help.");
        } else {
            sb.append(name != null ? ("Thank you so much, " + name + "! ") : "Thank you so much! ");
            sb.append("We're so glad you had a great experience and can't wait to see you again.");
        }
        return sb.toString();
    }

    private void emitDrafted(UUID tenantId, GbpReviewReply row) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reviewId", row.getReviewId());
        if (row.getRating() != null) payload.put("rating", row.getRating());
        if (row.getId() != null) payload.put("reviewReplyId", row.getId().toString());
        payload.put("source", "chairfill-paste-in");
        events.publish(DomainEvent.of(
                DomainEventType.GBP_REVIEW_REPLY_DRAFTED, tenantId,
                row.getId() != null ? row.getId() : UUID.randomUUID(), payload));
    }

    /**
     * The salon-flavored base system prompt — a warm, personal stylist/salon voice (vs the GBP
     * default's "owner-operated local service company"). The per-tenant
     * {@code kmosf.chairfill.review-system-prompt} is appended as an extra brand-tone hint.
     */
    static final String SALON_DEFAULT_SYSTEM_PROMPT =
            "You write a short public reply, on behalf of a salon / personal-care studio, to a "
            + "customer review left on the salon's Google Business Profile. Write in a warm, "
            + "personal, welcoming voice — like a beloved stylist who genuinely cares about each "
            + "client and the way they feel when they leave the chair, not a corporate brand. Thank "
            + "the reviewer by name when one is given, acknowledge something specific they mentioned "
            + "(their stylist, their service, how they felt), and keep it to 2-4 warm sentences. For "
            + "a glowing review, share genuine delight and invite them back for their next visit. For "
            + "a critical or low-star review, respond graciously and without defensiveness, take "
            + "responsibility, and warmly invite them to reach out so you can make it right. Do NOT "
            + "invent facts, prices, discounts, names, services, or details not present in the "
            + "review. Do NOT include placeholders, signatures, hashtags, or markdown — output ONLY "
            + "the reply text itself, nothing else.";
}
