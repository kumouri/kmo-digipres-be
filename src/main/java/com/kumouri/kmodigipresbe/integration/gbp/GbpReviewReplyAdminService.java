package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Backs the admin approve/post/skip surface for GBP review-reply drafts (NMM GBP review-reply
 * automation). Reads the tenant from the active (authenticated, admin-gated) reactive context — the
 * admin endpoints run in a real staff request, so the {@code TenantContext} is already present (the
 * {@code RecurringInvoiceService} / {@code ContractService} precedent).
 *
 * <p>This is the manual-approval counterpart to the default-OFF {@code GbpReviewPoller}'s optional
 * auto-post: the poller drafts and leaves the row {@code DRAFTED}; Rob lists the drafts, optionally
 * edits the text, then posts (or skips) one. Posting reuses {@link GbpApiClient#postReply} (the same
 * client the poller uses) — there is no second posting path.
 *
 * <h2>§9 reactive</h2>
 * Both single-row ops load the draft with an explicit tenant-scoped {@code findByTenantIdAndId};
 * {@code switchIfEmpty} is used <strong>only</strong> for the genuine not-found ({@code 4032}). A
 * non-{@code DRAFTED} row is rejected with an explicit status check ({@code 4033}) — never a
 * {@code switchIfEmpty(process)}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GbpReviewReplyAdminService {

    private final GbpReviewReplyRepository reviewReplies;
    private final GbpApiClient apiClient;
    private final DomainEventPublisher events;

    /** Lists the tenant's {@code DRAFTED} review replies (most-recent first) for Rob to review. */
    public Flux<GbpReviewReply> listDrafted() {
        return TenantContextHolder.required().flatMapMany(ctx ->
                reviewReplies.findByTenantIdAndStatusOrderByReceivedAtDesc(
                        ctx.tenantId(), GbpReviewReply.Status.DRAFTED));
    }

    /**
     * Posts the (optionally edited) reply for a {@code DRAFTED} draft back to Google, marks it
     * {@code POSTED}, and emits {@code GBP_REVIEW_REPLY_POSTED}. {@code 4032} if no such draft for
     * the tenant; {@code 4033} if the draft is not {@code DRAFTED}; {@code 4030}/{@code 4031} from
     * the reused {@link GbpApiClient#postReply}.
     *
     * @param id            the {@link GbpReviewReply} id
     * @param editedReply   an optional edited reply ({@code null}/blank = keep the AI draft)
     */
    public Mono<GbpReviewReply> post(UUID id, String editedReply) {
        return TenantContextHolder.required().flatMap(ctx ->
                reviewReplies.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "GBP review-reply draft not found", 4032, 404)))
                        .flatMap(row -> {
                            if (row.getStatus() != GbpReviewReply.Status.DRAFTED) {
                                return Mono.error(new DigiPresBeException(
                                        "GBP review-reply is not DRAFTED (status="
                                                + row.getStatus() + ") — cannot post", 4033, 409));
                            }
                            String reply = (editedReply != null && !editedReply.isBlank())
                                    ? editedReply
                                    : row.getDraftedReply();
                            if (reply == null || reply.isBlank()) {
                                return Mono.error(new DigiPresBeException(
                                        "GBP review-reply has no draft text and none was supplied "
                                                + "— nothing to post", 4033, 409));
                            }
                            row.setDraftedReply(reply);
                            return apiClient.postReply(row.getReviewId(), reply)
                                    .then(Mono.defer(() -> {
                                        row.setStatus(GbpReviewReply.Status.POSTED);
                                        row.setPostedAt(Instant.now());
                                        return reviewReplies.save(row);
                                    }))
                                    .doOnNext(posted -> emitPosted(ctx.tenantId(), posted));
                        }));
    }

    /**
     * Skips a {@code DRAFTED} draft (Rob chose not to reply) — marks it {@code SKIPPED}, no Google
     * call. {@code 4032} if no such draft; {@code 4033} if not {@code DRAFTED}.
     *
     * @param id the {@link GbpReviewReply} id
     */
    public Mono<GbpReviewReply> skip(UUID id) {
        return TenantContextHolder.required().flatMap(ctx ->
                reviewReplies.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                                "GBP review-reply draft not found", 4032, 404)))
                        .flatMap(row -> {
                            if (row.getStatus() != GbpReviewReply.Status.DRAFTED) {
                                return Mono.error(new DigiPresBeException(
                                        "GBP review-reply is not DRAFTED (status="
                                                + row.getStatus() + ") — cannot skip", 4033, 409));
                            }
                            row.setStatus(GbpReviewReply.Status.SKIPPED);
                            return reviewReplies.save(row);
                        }));
    }

    private void emitPosted(UUID tenantId, GbpReviewReply row) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reviewId", row.getReviewId());
        if (row.getId() != null) payload.put("reviewReplyId", row.getId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.GBP_REVIEW_REPLY_POSTED, tenantId,
                row.getId() != null ? row.getId() : UUID.randomUUID(), payload));
    }
}
