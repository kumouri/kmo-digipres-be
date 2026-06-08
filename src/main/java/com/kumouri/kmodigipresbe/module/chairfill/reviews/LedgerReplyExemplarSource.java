package com.kumouri.kmodigipresbe.module.chairfill.reviews;

import com.kumouri.kmodigipresbe.integration.gbp.GbpReplyDraftService.ReplyExemplar;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * ChairFill CF-4 — the shipped default {@link ReplyExemplarSource}: retrieves a salon's own
 * <strong>approved (POSTED)</strong> review replies from the {@code GbpReviewReply} ledger and
 * returns the most relevant few as voice exemplars for a new draft (RAG over past approved replies,
 * D4).
 *
 * <p><strong>Why the ledger, not the vector spine:</strong> the corpus we want is exactly "the
 * replies this salon has already approved" — which IS the POSTED {@code GbpReviewReply} rows. The
 * shipped {@code RagRetrievalService} vector path needs an OpenAI embedding call + an Atlas Vector
 * Search index, neither of which exists in CI / a fresh cluster (it falls back to empty there), so
 * for the PoC this ledger-backed retrieval is both more <em>literal</em> (the actual approved
 * corpus) and more robust. The {@link ReplyExemplarSource} seam keeps a future vector-backed source
 * a drop-in.
 *
 * <p><strong>Relevance ranking</strong> (lightweight, deterministic, no embeddings): prefer
 * exemplars whose rating is closest to the new review's rating (a 1★ reply best models the tone for
 * another 1★; a 5★ reply for a 5★), breaking ties toward the most recently posted. This gives the
 * model the most on-tone examples for the sentiment at hand.
 *
 * <p><strong>Best-effort:</strong> a query failure (or a missing tenant context) degrades to an
 * empty list — never an error — so the drafter falls back to a generic on-brand draft.
 */
@Slf4j
public class LedgerReplyExemplarSource implements ReplyExemplarSource {

    /** Pull a small candidate window from the ledger, then rank + trim to {@code limit}. */
    private static final int CANDIDATE_WINDOW = 25;

    private final GbpReviewReplyRepository reviewReplies;

    public LedgerReplyExemplarSource(GbpReviewReplyRepository reviewReplies) {
        this.reviewReplies = reviewReplies;
    }

    @Override
    public Mono<List<ReplyExemplar>> retrieve(UUID tenantId, Integer newReviewRating, int limit) {
        if (tenantId == null || limit <= 0) {
            return Mono.just(List.of());
        }
        return reviewReplies
                .findByTenantIdAndStatusOrderByPostedAtDesc(tenantId, GbpReviewReply.Status.POSTED)
                .take(CANDIDATE_WINDOW)
                .filter(r -> r.getDraftedReply() != null && !r.getDraftedReply().isBlank())
                .collectList()
                .map(rows -> rows.stream()
                        .sorted(Comparator.comparingInt(r -> ratingDistance(r, newReviewRating)))
                        .limit(limit)
                        .map(r -> new ReplyExemplar(
                                r.getRating(), r.getComment(), r.getDraftedReply()))
                        .toList())
                .onErrorResume(err -> {
                    log.warn("CF-4 exemplar retrieval failed for tenant {} (best-effort, "
                            + "degrading to no exemplars): {}", tenantId, err.toString());
                    return Mono.just(List.of());
                });
    }

    /**
     * Distance between an exemplar's rating and the new review's rating (lower = more on-tone). An
     * exemplar or new review with no rating is treated as a neutral mid distance so it still ranks
     * after exact-sentiment matches but ahead of opposite-sentiment ones.
     */
    private static int ratingDistance(GbpReviewReply exemplar, Integer newRating) {
        if (newRating == null || exemplar.getRating() == null) {
            return 2;
        }
        return Math.abs(exemplar.getRating() - newRating);
    }
}
