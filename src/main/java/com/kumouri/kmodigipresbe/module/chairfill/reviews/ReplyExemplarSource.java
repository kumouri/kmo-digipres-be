package com.kumouri.kmodigipresbe.module.chairfill.reviews;

import com.kumouri.kmodigipresbe.integration.gbp.GbpReplyDraftService.ReplyExemplar;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * ChairFill CF-4 — a pluggable source of on-brand <em>exemplar</em> past replies that ground a new
 * review-reply draft in a salon's own established voice (the "RAG over past replies" half of D4).
 *
 * <p>The seam is deliberately small so the retrieval strategy can evolve without touching the
 * drafter or the queue:
 * <ul>
 *   <li>the shipped default ({@link LedgerReplyExemplarSource}) retrieves the tenant's own
 *       <strong>approved (POSTED)</strong> {@code GbpReviewReply} rows — the literal corpus of
 *       "past approved replies" — ranked by relevance to the new review (rating proximity), needing
 *       no external embedding service or Atlas Vector index (so it is robust in CI and in a fresh
 *       cluster);</li>
 *   <li>a future implementation could back this with the shipped
 *       {@code RagRetrievalService}/{@code EmbeddingPipeline} vector spine once the salon's replies
 *       are embedded — same interface, drop-in.</li>
 * </ul>
 *
 * <p><strong>Best-effort contract:</strong> implementations MUST NOT propagate errors — a retrieval
 * failure returns {@code Mono.just(List.of())} (an empty exemplar list), so the drafter degrades to
 * a sensible generic on-brand draft rather than blocking (plan §4 / the GBP best-effort posture).
 */
public interface ReplyExemplarSource {

    /**
     * Retrieves up to {@code limit} on-brand exemplars for the given tenant, most relevant to a new
     * review with {@code newReviewRating}. Never errors — degrades to an empty list.
     *
     * @param tenantId        the tenant scope — mandatory
     * @param newReviewRating the star rating of the review being replied to (nullable) — used to
     *                        prefer exemplars of a similar sentiment (a critical reply best models
     *                        tone for another critical review)
     * @param limit           the maximum number of exemplars to return
     * @return the exemplars (possibly empty), never an error
     */
    Mono<List<ReplyExemplar>> retrieve(UUID tenantId, Integer newReviewRating, int limit);
}
