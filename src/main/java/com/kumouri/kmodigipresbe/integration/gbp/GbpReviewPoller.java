package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.integration.GbpReviewReply;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.gbp.GbpReviewReplyRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.util.HtmlUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Default-OFF poll-source that fetches new Google-Business-Profile reviews and drafts on-brand
 * replies for Rob to approve (NMM GBP review-reply automation). Structurally a
 * <strong>faithful mirror of the Phase-H {@code ImapInboundPoller}</strong> (default-OFF
 * {@code @Scheduled}, explicit-boolean idempotency) crossed with the {@code CoverageNudgeJob}
 * cross-tenant sweep (tenant iteration + synthetic {@code TenantContext} + ledger-insert-FIRST).
 *
 * <h2>DEFAULT-OFF (§7 hard boundary)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.gbp-reviews", name="enabled",
 * matchIfMissing=false)} — the bean is <strong>not even created in CI / any default run</strong>,
 * so <strong>no live Google fetch ever occurs</strong> unless a deployment explicitly opts in (the
 * {@code ImapInboundPoller} / {@code CoverageNudgeJob} posture). The {@code GbpApiClient} base URL
 * defaults to a non-routable {@code .invalid} host; combined with the default-OFF gate there is no
 * live Google traffic anywhere in the default/CI path. This poller is distinct from the
 * always-registerable admin approve/post controller (which is module-gated {@code matchIfMissing=true}).
 *
 * <h2>Idempotency: explicit-boolean review-id probe + ledger-insert-FIRST</h2>
 * For each fetched review the poller probes the {@link GbpReviewReply} ledger
 * ({@code findByTenantIdAndReviewId(...).map(e->true).defaultIfEmpty(false)}); only if not-yet-seen
 * does it <strong>insert the ledger row FIRST</strong> (unique {@code tenant_review_idx}, with
 * {@code onErrorResume(DuplicateKeyException → empty)} as the concurrent re-poll backstop) and then
 * draft + notify. <strong>Never {@code switchIfEmpty(process)}</strong> (the §9 trap). So a re-poll
 * of the same review id produces ZERO second draft / notify / event.
 *
 * <h2>AI is triage, not truth (plan §8)</h2>
 * The draft via {@link GbpReplyDraftService} is <strong>best-effort</strong> ({@code onErrorResume}):
 * a budget {@code 1200} / upstream {@code 1202} leaves the review <strong>ledgered</strong> (Rob
 * still sees it on the admin list) with a {@code null} {@code draftedReply} — an AI outage never
 * crashes the poll cycle nor drops a review.
 *
 * <h2>Optional auto-post</h2>
 * {@code kmosf.modules.gbp-reviews.auto-post=true} → after drafting, {@link GbpApiClient#postReply}
 * is called and the row is marked {@code POSTED} ({@code GBP_REVIEW_REPLY_POSTED}). Default false =
 * leave the draft {@code DRAFTED} for Rob to approve via the admin endpoint. A post failure is
 * swallowed (the draft stays {@code DRAFTED} — never lost).
 *
 * <h2>§9 reactive + blocking-I/O</h2>
 * The {@code @Scheduled} tick subscribes the chain on Spring's scheduler thread pool (never the
 * Netty event loop); the visible-for-test {@link #pollOnce()} returns a {@code Mono<Void>} the IT
 * blocks. {@code GbpApiClient}'s {@code WebClient} is non-blocking; the JSON/draft work is reactive;
 * there is no {@code .block()} on the loop. The only {@code switchIfEmpty}-style construct is the
 * explicit not-seen boolean default — no {@code switchIfEmpty(create/process)} anywhere.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kmosf.modules.gbp-reviews", name = "enabled",
        matchIfMissing = false)
public class GbpReviewPoller {

    private final TenantRepository tenants;
    private final IntegrationConnectionRepository connections;
    private final GbpApiClient apiClient;
    private final GbpReplyDraftService draftService;
    private final GbpReviewReplyRepository reviewReplies;
    private final EmailService emailService;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    private final boolean autoPost;
    private final String notifyFromAddress;

    public GbpReviewPoller(
            TenantRepository tenants,
            IntegrationConnectionRepository connections,
            GbpApiClient apiClient,
            GbpReplyDraftService draftService,
            GbpReviewReplyRepository reviewReplies,
            EmailService emailService,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            @Value("${kmosf.modules.gbp-reviews.auto-post:false}") boolean autoPost,
            @Value("${kmosf.mail.smtp.username:}") String notifyFromAddress) {
        this.tenants = tenants;
        this.connections = connections;
        this.apiClient = apiClient;
        this.draftService = draftService;
        this.reviewReplies = reviewReplies;
        this.emailService = emailService;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.autoPost = autoPost;
        this.notifyFromAddress = notifyFromAddress;
    }

    /**
     * Scheduled tick — fixed rate, default 15min ({@code kmosf.modules.gbp-reviews.poll-interval-ms}).
     * Fire-and-forget subscribe on the scheduler thread (never the Netty loop); the per-tenant /
     * per-review pipeline catches and logs so one failure never aborts the rest.
     */
    @Scheduled(
            fixedRateString = "${kmosf.modules.gbp-reviews.poll-interval-ms:900000}",
            initialDelayString = "${kmosf.modules.gbp-reviews.initial-delay-ms:60000}")
    public void scheduledTick() {
        pollOnce().subscribe(
                ignored -> {},
                err -> log.error("GbpReviewPoller tick failed", err));
    }

    /**
     * Visible-for-test entry — runs one full poll sweep across all tenants with a connected
     * {@code google-business} integration and returns when done, so an IT can drive it
     * deterministically (the {@code CoverageNudgeJob.nudgeDueOnce()} pattern).
     */
    public Mono<Void> pollOnce() {
        return tenants.findAll()
                .concatMap(t -> pollTenant(t.getId())
                        .onErrorResume(err -> {
                            log.warn("GBP review poll failed for tenant {}: {}",
                                    t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    /**
     * Polls one tenant — only if it has a connected {@code google-business} integration (a
     * non-GBP tenant is silently skipped, never a 4030 error). Fetch reviews under the synthetic
     * context, then process each.
     */
    private Mono<Void> pollTenant(UUID tenantId) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_GBP_REVIEWS"));
        return connections.findByTenantIdAndProvider(tenantId, GbpApiClient.PROVIDER)
                .flatMap(conn -> apiClient.fetchReviews()
                        .flatMapMany(Flux::fromIterable)
                        .concatMap(review -> processReview(tenantId, review)
                                .onErrorResume(err -> {
                                    log.warn("GBP review {} failed for tenant {}: {}",
                                            review.reviewId(), tenantId, err.toString());
                                    return Mono.empty();
                                }))
                        .then())
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /**
     * Process one review: explicit-boolean idempotency probe → (if new) ledger-insert-FIRST →
     * draft best-effort → update the row → notify Rob → {@code GBP_REVIEW_REPLY_DRAFTED} → optional
     * auto-post. Never {@code switchIfEmpty(process)}.
     */
    private Mono<Void> processReview(UUID tenantId, GbpReview review) {
        return reviewReplies.findByTenantIdAndReviewId(tenantId, review.reviewId())
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(seen -> {
                    if (seen) {
                        log.debug("GBP review {} already ledgered for tenant {} — skipping",
                                review.reviewId(), tenantId);
                        return Mono.empty();
                    }
                    return insertLedgerThenDraft(tenantId, review);
                });
    }

    /**
     * Ledger-insert-FIRST (the unique-index exactly-once backstop), then draft + notify + event. A
     * concurrent re-poll loses the insert on a {@code DuplicateKeyException} → {@code Mono.empty()}
     * = zero duplicate draft/notify/event.
     */
    private Mono<Void> insertLedgerThenDraft(UUID tenantId, GbpReview review) {
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
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("GBP review {} concurrent-fire lost ledger insert (tenant {}) — "
                            + "zero duplicate effect", review.reviewId(), tenantId);
                    return Mono.empty();
                })
                .flatMap(saved -> draftAndFinish(tenantId, review, saved));
    }

    /**
     * Draft (best-effort) → persist the draft onto the ledger row → notify Rob → emit
     * {@code GBP_REVIEW_REPLY_DRAFTED} → optional auto-post.
     */
    private Mono<Void> draftAndFinish(UUID tenantId, GbpReview review, GbpReviewReply saved) {
        return draftService.draftReply(review)
                .onErrorResume(e -> {
                    log.warn("GBP reply-draft failed for review {} (best-effort; review stays "
                            + "ledgered without a draft): {}", review.reviewId(), e.getMessage());
                    return Mono.empty();
                })
                .flatMap(draft -> {
                    saved.setDraftedReply(draft);
                    return reviewReplies.save(saved);
                })
                .defaultIfEmpty(saved)   // draft failed → keep the row as-is (draftedReply == null)
                .flatMap(persisted -> notifyRob(persisted, review)
                        .then(Mono.fromRunnable(() -> emitDrafted(tenantId, persisted)))
                        .then(maybeAutoPost(tenantId, persisted)));
    }

    /**
     * Optional auto-post: if {@code kmosf.modules.gbp-reviews.auto-post=true} AND a draft exists,
     * post it back to Google and mark the row {@code POSTED} ({@code GBP_REVIEW_REPLY_POSTED}). A
     * post failure is swallowed (the draft stays {@code DRAFTED} — never lost). Default = no-op.
     */
    private Mono<Void> maybeAutoPost(UUID tenantId, GbpReviewReply row) {
        if (!autoPost || row.getDraftedReply() == null || row.getDraftedReply().isBlank()) {
            return Mono.empty();
        }
        return apiClient.postReply(row.getReviewId(), row.getDraftedReply())
                .then(Mono.defer(() -> {
                    row.setStatus(GbpReviewReply.Status.POSTED);
                    row.setPostedAt(Instant.now());
                    return reviewReplies.save(row)
                            .doOnNext(posted -> emitPosted(tenantId, posted))
                            .then();
                }))
                .onErrorResume(e -> {
                    log.warn("GBP auto-post failed for review {} (draft stays DRAFTED for manual "
                            + "approval): {}", row.getReviewId(), e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * Best-effort notify Rob — email + SMS — to the per-tenant targets in the Twilio
     * {@code IntegrationConnection.config} ({@code notifyEmail} / {@code notifyPhone}); NOT
     * hardcoded (the {@code MoleTriageService} / {@code TwilioVoicemailService} notify precedent). A
     * missing connection / missing target / send failure is swallowed so the already-durable draft
     * is never lost.
     */
    private Mono<Void> notifyRob(GbpReviewReply row, GbpReview review) {
        return connections.findByTenantIdAndProvider(row.getTenantId(), TwilioSmsService.PROVIDER)
                .flatMap(conn -> dispatchNotify(conn, row, review))
                .onErrorResume(e -> {
                    log.warn("GBP review notify failed (best-effort, ignored): {}", e.getMessage());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> dispatchNotify(IntegrationConnection conn, GbpReviewReply row,
                                      GbpReview review) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");

        String reviewer = review.reviewerName() != null ? review.reviewerName() : "a customer";
        String stars = review.rating() != null ? review.rating() + "★" : "no rating";
        String headline = "New Google review from " + reviewer + " (" + stars + ")";
        boolean hasDraft = row.getDraftedReply() != null && !row.getDraftedReply().isBlank();

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            StringBuilder html = new StringBuilder();
            html.append("<p>").append(HtmlUtils.htmlEscape(headline)).append("</p>");
            if (review.comment() != null) {
                html.append("<p>Review: ").append(HtmlUtils.htmlEscape(review.comment())).append("</p>");
            }
            if (hasDraft) {
                html.append("<p>Suggested reply (review &amp; approve):</p><blockquote>")
                        .append(HtmlUtils.htmlEscape(row.getDraftedReply())).append("</blockquote>");
            } else {
                html.append("<p>(No draft could be generated — please write a reply manually.)</p>");
            }
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject(headline)
                    .body(html.toString())
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("GBP review notify-email failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }

        Mono<Void> smsMono = Mono.empty();
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            String smsBody = headline + (hasDraft
                    ? " — a suggested reply is drafted for your approval."
                    : " — please reply manually.");
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(smsBody)
                    .build();
            smsMono = twilioSmsService.sendSms(smsReq)
                    .onErrorResume(e -> {
                        log.warn("GBP review notify-SMS failed (best-effort, ignored): {}",
                                e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    private void emitDrafted(UUID tenantId, GbpReviewReply row) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("reviewId", row.getReviewId());
        if (row.getRating() != null) payload.put("rating", row.getRating());
        if (row.getId() != null) payload.put("reviewReplyId", row.getId().toString());
        payload.put("autoPost", autoPost);
        events.publish(DomainEvent.of(
                DomainEventType.GBP_REVIEW_REPLY_DRAFTED, tenantId,
                row.getId() != null ? row.getId() : UUID.randomUUID(), payload));
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
