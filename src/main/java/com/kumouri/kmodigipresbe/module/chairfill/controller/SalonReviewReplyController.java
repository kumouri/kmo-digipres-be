package com.kumouri.kmodigipresbe.module.chairfill.controller;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.module.chairfill.ChairFillAutoConfiguration;
import com.kumouri.kmodigipresbe.module.chairfill.reviews.SalonReviewReplyService;
import com.kumouri.kmodigipresbe.module.chairfill.reviews.SalonReviewReplyService.PasteInReview;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.time.Instant;

/**
 * ChairFill CF-4 — the salon review-reply <strong>paste-in</strong> surface. A staff member pastes a
 * customer review (text + optional rating/author); the {@link SalonReviewReplyService} drafts an
 * on-brand, salon-voiced reply (RAG-grounded in the salon's past approved replies) and parks it
 * DRAFTED in the <strong>same approval queue NMM uses</strong> — the unchanged
 * {@code GbpReviewReplyAdminController} ({@code GET /gbp/review-replies}, {@code POST
 * /{id}/post}|{@code /{id}/skip}) lists, approves (posts or copy-ready), or skips it. <strong>Never
 * auto-posted.</strong>
 *
 * <p>This is the demo / de-risked entry point: it needs <strong>no live Google OAuth</strong>. A
 * salon that later connects {@code google-business} can also approve→post via the reused admin path;
 * one without it edits the on-brand draft and copies it into the GBP console (copy-ready).
 *
 * <h2>Gating</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.chairfill", name="enabled")} — absent
 *       from the OpenAPI spec when the module is off (the {@code NoShowRiskController} /
 *       {@code WaitlistWidgetController} precedent), so a non-chairfill deployment never registers
 *       it;</li>
 *   <li>per-tenant module membership via {@link TenantModuleRegistry#requireEnabled(String)} (the
 *       {@code SalonBookingController}/{@code NoShowRiskController} precedent — surfaces the shared
 *       1130/1132 module-gate codes for a tenant without {@code chairfill});</li>
 *   <li>{@link RoleGuard#requireRole "STAFF"} — any salon staffer can submit a review to draft a
 *       reply ({@code 1800} otherwise). Approval/posting stays ADMIN-gated on the reused admin
 *       surface.</li>
 * </ul>
 *
 * <p>The base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so this maps to
 * {@code POST /api/v1/chairfill/reviews/draft}.
 */
@RestController
@RequestMapping("/chairfill/reviews")
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
@RequiredArgsConstructor
public class SalonReviewReplyController {

    private final SalonReviewReplyService service;
    private final TenantModuleRegistry modules;

    /**
     * Drafts an on-brand salon reply for a pasted-in review and queues it DRAFTED for approval.
     * STAFF-gated + chairfill-module-gated. {@code 4240} if the review text is blank; AI failures
     * degrade to a generic on-brand draft (never an error). Returns the queued {@link GbpReviewReply}
     * (the same shape the admin queue lists).
     *
     * @param body the pasted review
     */
    @PostMapping("/draft")
    public Mono<GbpReviewReply> draft(@RequestBody PasteInReviewRequest body) {
        if (body == null || body.comment() == null || body.comment().isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "A review text (comment) is required to draft a reply", 4240, 400));
        }
        PasteInReview paste = new PasteInReview(
                body.externalReviewId(),
                body.rating(),
                body.comment().trim(),
                body.reviewerName(),
                body.createTime());
        return modules.requireEnabled(ChairFillAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("STAFF"))
                .then(service.draftPasteIn(paste));
    }

    /**
     * The paste-in request body. Only {@code comment} (the review text) is required; everything else
     * is optional (the prompt skips anything not provided rather than inventing it).
     *
     * @param externalReviewId an optional stable id (e.g. a real GBP review id, if known) — nullable
     * @param rating           the star rating 1..5 — nullable
     * @param comment          the review text — required ({@code 4240} if blank)
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
