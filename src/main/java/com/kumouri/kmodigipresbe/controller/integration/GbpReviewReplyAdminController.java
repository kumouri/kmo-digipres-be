package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.integration.gbp.GbpReviewReplyAdminService;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.GetMapping;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Admin approve/post surface for GBP review-reply drafts (NMM GBP review-reply automation). Lets Rob
 * review the on-brand replies the default-OFF {@code GbpReviewPoller} drafted, optionally edit one,
 * and post it back to Google (or skip it).
 *
 * <h2>Gating</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.gbp-reviews", name="enabled",
 *       matchIfMissing=true)} — this is the <strong>always-registerable admin surface</strong>
 *       (a disabled module → endpoint not registered → 404, the Phase-1/2/3 module-gate precedent).
 *       It is deliberately <strong>distinct from the {@code GbpReviewPoller}</strong>, which is
 *       gated {@code matchIfMissing=false} (DEFAULT-OFF, no live fetch). So a deployment can leave
 *       the poller off (no live Google polling) while the admin surface stays available — and
 *       turning the whole module off via {@code kmosf.modules.gbp-reviews.enabled=false} removes
 *       both the poller and this controller.</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint — only tenant admins approve/post
 *       ({@code 1800} otherwise).</li>
 * </ul>
 *
 * <p>The base path {@code /api/v1} is applied by {@code spring.webflux.base-path}, so these map to
 * {@code GET/POST /api/v1/gbp/review-replies...}.
 */
@RestController
@RequestMapping("/gbp/review-replies")
@ConditionalOnProperty(prefix = "kmosf.modules.gbp-reviews", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class GbpReviewReplyAdminController {

    private final GbpReviewReplyAdminService service;

    /** Lists the tenant's DRAFTED review replies (most-recent first). ADMIN-gated. */
    @GetMapping
    public Flux<GbpReviewReply> listDrafted() {
        return RoleGuard.requireRole("ADMIN").thenMany(service.listDrafted());
    }

    /**
     * Posts the (optionally edited) reply for a DRAFTED draft back to Google and marks it POSTED.
     * ADMIN-gated. {@code @IdempotentRoute}: a retry with the same {@code Idempotency-Key} replays
     * the original 2xx (belt over the DRAFTED→POSTED status-machine guard, which already returns
     * {@code 4033} on a second post). Returns the updated {@link GbpReviewReply} — a non-empty body
     * is also required for the {@code @IdempotentRoute} response tee. {@code 4032} not-found,
     * {@code 4033} not-DRAFTED, {@code 4030}/{@code 4031} reused GBP client.
     *
     * @param id   the review-reply id
     * @param body optional edited reply ({@code null}/blank = keep the AI draft)
     */
    @PostMapping("/{id}/post")
    @IdempotentRoute
    public Mono<GbpReviewReply> post(@PathVariable UUID id,
                                     @RequestBody(required = false) PostReplyRequest body) {
        String editedReply = body == null ? null : body.reply();
        return RoleGuard.requireRole("ADMIN").then(service.post(id, editedReply));
    }

    /**
     * Skips a DRAFTED draft (marks it SKIPPED — no Google call). ADMIN-gated. {@code 4032}
     * not-found, {@code 4033} not-DRAFTED.
     *
     * @param id the review-reply id
     */
    @PostMapping("/{id}/skip")
    public Mono<GbpReviewReply> skip(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(service.skip(id));
    }

    /**
     * The optional edited-reply body for {@code POST /{id}/post}. A staff member may tweak the AI
     * draft before it goes live; a {@code null}/blank {@code reply} keeps the stored draft.
     *
     * @param reply the edited reply text (nullable)
     */
    public record PostReplyRequest(String reply) {
    }
}
