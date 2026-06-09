package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E3 Review Engine — the post-visit review-REQUEST sender (a faithful clone of the Phase-3
 * {@code CoverageNudgeJob} cross-tenant default-OFF scheduled sender). Sends due PENDING
 * {@link ReviewRequest}s as a <strong>frictionless, no-incentive</strong> Google-review SMS asking the
 * contact to leave a review (Google's 2026 policy bans review incentives, so the template offers nothing
 * in return).
 *
 * <h2>DEFAULT-OFF (§7 hard boundary)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.review-engine", name="sender-enabled",
 * matchIfMissing=false)} — the bean is <strong>not even created in CI / any default run</strong>, so
 * <strong>no live review-request SMS ever goes out</strong> unless a deployment explicitly opts in (the
 * {@code CoverageNudgeJob} / {@code ImapInboundPoller} posture; there is deliberately NO
 * {@code sender-enabled} line in {@code application.properties}). The {@code ReviewRequestService}
 * creator runs always (a harmless PENDING row), but nothing is texted until the sender is opted in.
 *
 * <h2>Per-tenant Google review link (NOT hardcoded)</h2>
 * The link comes from the per-tenant Twilio {@link IntegrationConnection}'s
 * {@code config["reviewLink"]} (the same connection that carries the notify targets). A tenant with no
 * {@code reviewLink} is silently skipped (no send, no status change) so a deployment opts in by setting
 * it — there is no hardcoded URL anywhere.
 *
 * <h2>Send idempotency — atomic PENDING→SENT claim, never {@code switchIfEmpty(send)}</h2>
 * The sender <strong>atomically claims</strong> a still-PENDING row with a conditional
 * {@code findAndModify} ({@code {_id, status:PENDING} → {status:SENT, sentAt:now}}, {@code returnNew}):
 * a returned doc = the winner (we flipped it) and only the winner sends the SMS; {@code null} = the row
 * was already claimed (a concurrent second sweep / restart) → zero duplicate send. This is the
 * ledger-insert-FIRST exactly-once guarantee expressed as an atomic status claim on the request itself
 * (the {@code WaitlistClaimService} per-slot {@code findAndModify} precedent — one fewer collection than
 * a separate send-log). <strong>Never {@code switchIfEmpty(send)}</strong> (the §9 #2 trap).
 *
 * <h2>Consent + frequency cap</h2>
 * Before claiming: the contact must have a phone and must NOT carry the {@value #SMS_OPT_OUT_TAG} tag
 * (honors STOP), and a <strong>per-contact rolling frequency cap</strong>
 * ({@code kmosf.review-engine.max-per-contact-per-window} SENT requests over {@code …window-hours})
 * prevents over-asking a contact with several recent visits. A gate-blocked request is marked
 * {@code SKIPPED} (it will not be retried for that contact+subject) and emits
 * {@code REVIEW_REQUEST_SKIPPED}.
 *
 * <h2>§9 reactive + blocking-I/O</h2>
 * The {@code @Scheduled} tick subscribes on Spring's scheduler thread pool (never the Netty loop); the
 * visible-for-test {@link #sendDueOnce()} returns a {@code Mono<Void>} the IT blocks. The SMS goes
 * through the non-blocking {@link TwilioSmsService}. The only {@code switchIfEmpty}-style construct is
 * the explicit not-found short-circuit — no {@code switchIfEmpty(create/send)}.
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "kmosf.modules.review-engine", name = "sender-enabled",
        matchIfMissing = false)
public class ReviewRequestSenderJob {

    /** A Contact carrying this tag has opted out of SMS (STOP). No review request is ever sent to them. */
    public static final String SMS_OPT_OUT_TAG = "sms-opt-out";

    private final TenantRepository tenants;
    private final ReviewRequestRepository reviewRequests;
    private final ContactRepository contacts;
    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;
    private final ReactiveMongoTemplate mongo;

    private final String requestMessage;
    private final int maxPerContactPerWindow;
    private final long windowHours;

    public ReviewRequestSenderJob(
            TenantRepository tenants,
            ReviewRequestRepository reviewRequests,
            ContactRepository contacts,
            IntegrationConnectionRepository connections,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher events,
            ReactiveMongoTemplate mongo,
            @Value("${kmosf.review-engine.request-message:"
                    + "Thanks for choosing us! If you have a moment, we'd really appreciate a quick "
                    + "Google review — it helps your neighbors find us. {link}}") String requestMessage,
            @Value("${kmosf.review-engine.max-per-contact-per-window:1}") int maxPerContactPerWindow,
            @Value("${kmosf.review-engine.window-hours:720}") long windowHours) {
        this.tenants = tenants;
        this.reviewRequests = reviewRequests;
        this.contacts = contacts;
        this.connections = connections;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
        this.mongo = mongo;
        this.requestMessage = requestMessage;
        this.maxPerContactPerWindow = maxPerContactPerWindow;
        this.windowHours = windowHours;
    }

    /**
     * Scheduled tick — fixed delay, default 1h ({@code kmosf.review-engine.send-interval-ms}).
     * Fire-and-forget subscribe on the scheduler thread (never the Netty loop); the per-tenant /
     * per-request pipeline catches and logs so one failure never aborts the rest.
     */
    @Scheduled(
            fixedDelayString = "${kmosf.review-engine.send-interval-ms:3600000}",
            initialDelayString = "${kmosf.review-engine.initial-delay-ms:60000}")
    public void scheduledTick() {
        sendDueOnce().subscribe(
                ignored -> {},
                err -> log.error("ReviewRequestSenderJob tick failed", err));
    }

    /**
     * Visible-for-test entry — runs one full send sweep across all tenants and returns when done, so an
     * IT can drive it deterministically (the {@code CoverageNudgeJob.nudgeDueOnce()} pattern).
     */
    public Mono<Void> sendDueOnce() {
        Instant now = Instant.now();
        return tenants.findAll()
                .concatMap(t -> sendTenant(t.getId(), now)
                        .onErrorResume(err -> {
                            log.warn("Review-request send sweep failed for tenant {}: {}",
                                    t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> sendTenant(UUID tenantId, Instant now) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("AUTOMATION_REVIEW_ENGINE"));
        return resolveReviewLink(tenantId)
                .flatMap(link -> reviewRequests
                        .findByTenantIdAndStatusAndDueAtBefore(tenantId, ReviewRequest.Status.PENDING, now)
                        .concatMap(req -> processRequest(tenantId, req, link)
                                .onErrorResume(err -> {
                                    log.warn("Review-request send failed for request {} (tenant {}): {}",
                                            req.getId(), tenantId, err.toString());
                                    return Mono.empty();
                                }))
                        .then())
                // No reviewLink for this tenant → skip the whole tenant (no send, requests stay PENDING).
                .switchIfEmpty(Mono.fromRunnable(() ->
                        log.debug("Tenant {} has no review link configured — skipping review-request send",
                                tenantId)))
                .contextWrite(TenantContextHolder.write(ctx))
                .then();
    }

    /**
     * Process one due PENDING request: resolve contact → consent + frequency-cap gates → atomic
     * PENDING→SENT claim → send SMS (winner only). A gate skip marks the row SKIPPED.
     */
    private Mono<Void> processRequest(UUID tenantId, ReviewRequest req, String link) {
        if (req.getContactId() == null) {
            return markSkipped(tenantId, req, "no-contact");
        }
        return contacts.findByTenantIdAndId(tenantId, req.getContactId())
                .flatMap(contact -> gateThenClaim(tenantId, req, contact, link))
                // Contact no longer exists → skip (don't keep retrying forever).
                .switchIfEmpty(Mono.defer(() -> markSkipped(tenantId, req, "contact-missing")));
    }

    private Mono<Void> gateThenClaim(UUID tenantId, ReviewRequest req, Contact contact, String link) {
        if (hasOptedOut(contact)) {
            return markSkipped(tenantId, req, "opted-out");
        }
        String phone = firstPhone(contact);
        if (phone == null) {
            return markSkipped(tenantId, req, "no-phone");
        }
        Instant windowStart = Instant.now().minus(Duration.ofHours(windowHours));
        return reviewRequests.countByTenantIdAndContactIdAndStatusAndSentAtAfter(
                        tenantId, contact.getId(), ReviewRequest.Status.SENT, windowStart)
                .defaultIfEmpty(0L)
                .flatMap(recent -> {
                    if (recent >= maxPerContactPerWindow) {
                        return markSkipped(tenantId, req, "frequency-cap");
                    }
                    return claimThenSend(tenantId, req, phone, link);
                });
    }

    /**
     * Atomic PENDING→SENT claim (the exactly-once seam). A returned doc = winner → send; {@code null} =
     * already claimed by a concurrent sweep → zero duplicate send. Never {@code switchIfEmpty(send)}.
     */
    private Mono<Void> claimThenSend(UUID tenantId, ReviewRequest req, String phone, String link) {
        Query query = new Query(Criteria.where("_id").is(req.getId())
                .and("tenantId").is(tenantId)
                .and("status").is(ReviewRequest.Status.PENDING));
        Update update = new Update()
                .set("status", ReviewRequest.Status.SENT)
                .set("sentAt", Instant.now());
        FindAndModifyOptions opts = FindAndModifyOptions.options().returnNew(true);
        return mongo.findAndModify(query, update, opts, ReviewRequest.class)
                .flatMap(claimed -> sendSms(phone, link)
                        .then(Mono.fromRunnable(() -> emitSent(tenantId, claimed))))
                // null returned = lost the claim (already SENT by a concurrent sweep) → zero duplicate.
                .then();
    }

    private Mono<Void> sendSms(String phone, String link) {
        String body = requestMessage.contains("{link}")
                ? requestMessage.replace("{link}", link)
                : requestMessage + " " + link;
        SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                .to(new PhoneContact(phone))
                .body(body)
                .build();
        return twilioSmsService.sendSms(smsReq).then();
    }

    private Mono<Void> markSkipped(UUID tenantId, ReviewRequest req, String reason) {
        // Atomic PENDING→SKIPPED so a concurrent sweep doesn't double-handle; emit only if we flipped it.
        Query query = new Query(Criteria.where("_id").is(req.getId())
                .and("tenantId").is(tenantId)
                .and("status").is(ReviewRequest.Status.PENDING));
        Update update = new Update().set("status", ReviewRequest.Status.SKIPPED);
        FindAndModifyOptions opts = FindAndModifyOptions.options().returnNew(true);
        return mongo.findAndModify(query, update, opts, ReviewRequest.class)
                .doOnNext(skipped -> emitSkipped(tenantId, skipped, reason))
                .then();
    }

    /** Resolves the tenant's Google review link from the Twilio connection's config["reviewLink"]. */
    private Mono<String> resolveReviewLink(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, TwilioSmsService.PROVIDER)
                .mapNotNull(conn -> conn.getConfig() == null ? null : conn.getConfig().get("reviewLink"))
                .filter(link -> link != null && !link.isBlank());
    }

    private boolean hasOptedOut(Contact contact) {
        Set<String> tags = contact.getTags();
        return tags != null && tags.contains(SMS_OPT_OUT_TAG);
    }

    private String firstPhone(Contact contact) {
        List<PhoneNumber> phones = contact.getPhones();
        if (phones == null) return null;
        for (PhoneNumber p : phones) {
            if (p != null && p.number() != null && !p.number().isBlank()) {
                return p.number();
            }
        }
        return null;
    }

    private void emitSent(UUID tenantId, ReviewRequest req) {
        Map<String, Object> payload = new HashMap<>();
        if (req.getId() != null) payload.put("reviewRequestId", req.getId().toString());
        payload.put("subjectType", req.getSubjectType() == null ? null : req.getSubjectType().name());
        if (req.getSubjectId() != null) payload.put("subjectId", req.getSubjectId().toString());
        if (req.getContactId() != null) payload.put("contactId", req.getContactId().toString());
        events.publish(DomainEvent.of(
                DomainEventType.REVIEW_REQUEST_SENT, tenantId,
                req.getId() != null ? req.getId() : UUID.randomUUID(), payload));
    }

    private void emitSkipped(UUID tenantId, ReviewRequest req, String reason) {
        Map<String, Object> payload = new HashMap<>();
        if (req.getId() != null) payload.put("reviewRequestId", req.getId().toString());
        if (req.getContactId() != null) payload.put("contactId", req.getContactId().toString());
        payload.put("reason", reason);
        events.publish(DomainEvent.of(
                DomainEventType.REVIEW_REQUEST_SKIPPED, tenantId,
                req.getId() != null ? req.getId() : UUID.randomUUID(), payload));
    }
}
