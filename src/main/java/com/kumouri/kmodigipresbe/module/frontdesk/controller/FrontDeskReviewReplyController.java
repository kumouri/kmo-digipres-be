package com.kumouri.kmodigipresbe.module.frontdesk.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.reviews.FrontDeskReviewReplyService;
import com.kumouri.kmodigipresbe.module.frontdesk.reviews.FrontDeskReviewReplyService.DraftedReply;
import com.kumouri.kmodigipresbe.module.frontdesk.reviews.FrontDeskReviewReplyService.PasteInReview;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

/**
 * FrontDesk IQ (FD-4 — HIPAA-safe review-reply) — the health-practice review-reply surface: a
 * <strong>paste-in</strong> drafter + a self-contained draft → approve/skip queue. A staffer pastes a public
 * review; {@link FrontDeskReviewReplyService} drafts a <strong>HIPAA-guardrailed</strong> reply (thank /
 * apologize / invite-offline — never confirming patient status or naming a procedure, fence F4), runs the
 * deterministic {@link com.kumouri.kmodigipresbe.module.frontdesk.reviews.HipaaReplyLint} over it, and parks
 * it DRAFTED. The staffer then lists the drafts (with their lint flags), approves one (copy-ready) or skips
 * it. <strong>Never auto-posted.</strong>
 *
 * <p>Distinct from the shared GBP admin queue ({@code GbpReviewReplyAdminController}, ADMIN-gated, posts live
 * to Google): this is the FrontDesk demo path — module-gated, STAFF-gated, and approval is
 * <strong>copy-ready</strong> (no GBP OAuth, no live Google call). It does not modify the shared
 * {@code GbpReviewReply} model or the GBP admin surface, so NMM / ChairFill review-reply stay byte-equivalent.
 *
 * <h2>Gating</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.frontdesk", name="enabled")} — absent from the
 *       OpenAPI spec when the module is off (the {@code NoShowRiskController} precedent), so a non-frontdesk
 *       deployment never registers it;</li>
 *   <li>per-tenant module membership via {@link TenantModuleRegistry#requireEnabled(String)} on every handler
 *       (the shared 1130/1132 module-gate codes);</li>
 *   <li>{@link RoleGuard#requireRole "STAFF"} — any front-desk staffer can draft, list, approve, or skip
 *       ({@code 1800} otherwise). (Approval is copy-ready, not a live post, so it stays STAFF-gated unlike the
 *       ADMIN-gated live GBP post.)</li>
 * </ul>
 *
 * <p>The base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so these map to
 * {@code GET/POST /api/v1/frontdesk/reviews...}.
 */
@RestController
@RequestMapping("/frontdesk/reviews")
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@RequiredArgsConstructor
public class FrontDeskReviewReplyController {

    private final FrontDeskReviewReplyService service;
    private final TenantModuleRegistry modules;

    /**
     * Drafts a HIPAA-safe reply for a pasted-in review and queues it DRAFTED for approval. STAFF-gated +
     * frontdesk-module-gated. {@code 4290} if the review text is blank; AI failures degrade to a generic
     * HIPAA-safe draft (never an error). Returns the queued draft + its HIPAA-lint flags.
     *
     * @param body the pasted review
     */
    @PostMapping("/draft")
    public Mono<DraftedReply> draft(@RequestBody PasteInReviewRequest body) {
        if (body == null || body.comment() == null || body.comment().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "A review text (comment) is required to draft a reply", 4290, 400));
        }
        PasteInReview paste = new PasteInReview(
                body.externalReviewId(),
                body.rating(),
                body.comment().trim(),
                body.reviewerName(),
                body.createTime());
        return guard()
                .then(RoleGuard.requireRole("STAFF"))
                .then(service.draftPasteIn(paste));
    }

    /** Lists the tenant's DRAFTED review replies (most-recent first), each with its HIPAA-lint flags. */
    @GetMapping
    public Flux<DraftedReply> listDrafted() {
        return guard().then(RoleGuard.requireRole("STAFF")).thenMany(service.listDrafted());
    }

    /**
     * Approves a DRAFTED draft as copy-ready — marks it POSTED (no live Google call; the staffer copies the
     * reply into the GBP console) and removes it from the DRAFTED queue. STAFF-gated. {@code 4292} not-found,
     * {@code 4291} not-DRAFTED.
     *
     * @param id the review-reply id
     */
    @PostMapping("/{id}/approve")
    public Mono<DraftedReply> approve(@PathVariable UUID id) {
        return guard().then(RoleGuard.requireRole("STAFF")).then(service.approve(id));
    }

    /**
     * Skips a DRAFTED draft (marks it SKIPPED — no Google call). STAFF-gated. {@code 4292} not-found,
     * {@code 4291} not-DRAFTED.
     *
     * @param id the review-reply id
     */
    @PostMapping("/{id}/skip")
    public Mono<DraftedReply> skip(@PathVariable UUID id) {
        return guard().then(RoleGuard.requireRole("STAFF")).then(service.skip(id));
    }

    private Mono<Void> guard() {
        return modules.requireEnabled(FrontDeskAutoConfiguration.MODULE_KEY);
    }

    /**
     * The paste-in request body. Only {@code comment} (the review text) is required; everything else is
     * optional (the prompt skips anything not provided rather than inventing it).
     *
     * @param externalReviewId an optional stable id (e.g. a real GBP review id, if known) — nullable
     * @param rating           the star rating 1..5 — nullable
     * @param comment          the review text — required ({@code 4290} if blank)
     * @param reviewerName     the reviewer's display name — nullable
     * @param createTime       when the review was left — nullable (defaults to now)
     */
    public record PasteInReviewRequest(
            String externalReviewId,
            Integer rating,
            String comment,
            String reviewerName,
            Instant createTime) {
    }
}
