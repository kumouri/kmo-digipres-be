package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T11 (Home "QuoteCloser") — the enrollment + stop sweep. A <strong>DEFAULT-OFF</strong> cross-tenant
 * {@code @Scheduled} job (the {@code CoverageNudgeJob} / {@code NurtureRunner} / {@code ReviewRequestSenderJob}
 * posture) that drives the un-accepted-quote nurture composition:
 *
 * <ol>
 *   <li><strong>Enroll</strong> — each tenant {@code QuoteRequest} that has sat {@link QuoteStatus#NEW}
 *       (un-accepted) longer than the per-tenant {@link QuoteCloserConfig#getUnacceptedWindowHours() window}
 *       has its contact enrolled into the tenant's configured QuoteCloser {@code NurtureCampaign} (E1). The
 *       default-OFF {@code NurtureRunner} then drives the reminder → financing-nudge → last-call cadence.</li>
 *   <li><strong>Stop</strong> (defense-in-depth) — each active QuoteCloser-campaign enrollment whose contact
 *       no longer has ANY {@code NEW} quote (the quote was ACCEPTED / DECLINED / BOOKED, e.g. set by the
 *       office without a {@code QUOTE_ACCEPTED} event) is exited ({@code EXITED}). The prompt accept-time stop
 *       is the {@code QuoteWonSubscriber}; this sweep covers the office-set DECLINED/BOOKED transitions.</li>
 * </ol>
 *
 * <h2>DEFAULT-OFF (the §2 directive / §7 boundary — no live enroll in CI / any default run)</h2>
 * It is a {@code @Bean} in the both-module {@code QuoteCloserAutoConfiguration} (so it exists only when
 * quoting AND nurture are on), AND that {@code @Bean} method carries
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.quote-closer-job", name="enabled",
 * matchIfMissing=false)} — the bean is not even created unless a deployment opts in (the
 * {@code CoverageNudgeJob} precedent). So the config + analytics surfaces can be administered while the
 * sweep stays OFF. There is deliberately no {@code enabled} line in {@code application.properties}. Note:
 * enrolling does NOT itself send — the {@code NurtureRunner} (also default-OFF) is the sender, so even an
 * opted-in sweep sends nothing unless the runner is ALSO opted in.
 *
 * <h2>Idempotent enroll — explicit-boolean, NEVER {@code switchIfEmpty(create/enroll)}</h2>
 * The enroll is the E1 explicit-boolean find-or-enroll over the unique
 * {@code NurtureEnrollment.tenant_campaign_contact_idx}
 * ({@code findBy…CampaignIdAndContactId(...).map(true).defaultIfEmpty(false)} then a ledger-style insert with
 * {@code onErrorResume(DuplicateKeyException → false)}) — the {@code TierRoutingService.enrollIfAbsent} /
 * {@code NurtureSegmentationService.enrollIfAbsent} pattern. A re-run (or a second aged quote for the same
 * contact) does ZERO duplicate enroll.
 *
 * <h2>§9 reactive</h2>
 * The {@code @Scheduled} tick fire-and-forget subscribes on the scheduler pool (never the Netty loop); the
 * visible-for-test {@link #sweepDueOnce()} returns a {@code Mono<Void>} the IT blocks; effects run under a
 * synthetic {@code TenantContext(tenantId, null, Set.of(SYSTEM_ROLE))}. The only {@code switchIfEmpty}-style
 * construct is the explicit not-seen boolean default — no {@code switchIfEmpty(create/enroll)}.
 */
@Slf4j
public class QuoteCloserEnrollmentJob {

    public static final String SYSTEM_ROLE = "AUTOMATION_QUOTE_CLOSER";

    /** The dormancy tier label recorded on a QuoteCloser enrollment (these are "A"-band un-accepted leads). */
    private static final DormancyBucket BUCKET = DormancyBucket.A;

    private final TenantRepository tenants;
    private final QuoteCloserConfigRepository configs;
    private final QuoteRequestRepository quotes;
    private final NurtureCampaignRepository campaigns;
    private final NurtureEnrollmentRepository enrollments;
    private final DomainEventPublisher events;
    private final Clock clock;

    public QuoteCloserEnrollmentJob(TenantRepository tenants,
                                    QuoteCloserConfigRepository configs,
                                    QuoteRequestRepository quotes,
                                    NurtureCampaignRepository campaigns,
                                    NurtureEnrollmentRepository enrollments,
                                    DomainEventPublisher events,
                                    Clock clock) {
        this.tenants = tenants;
        this.configs = configs;
        this.quotes = quotes;
        this.campaigns = campaigns;
        this.enrollments = enrollments;
        this.events = events;
        this.clock = clock;
    }

    /**
     * Scheduled tick — fixed delay, default 1h ({@code kmosf.modules.quote-closer-job.interval-ms}).
     * Fire-and-forget subscribe on the scheduler thread (never the Netty loop).
     */
    @Scheduled(
            fixedDelayString = "${kmosf.modules.quote-closer-job.interval-ms:3600000}",
            initialDelayString = "${kmosf.modules.quote-closer-job.initial-delay-ms:60000}")
    public void scheduledTick() {
        sweepDueOnce().subscribe(
                ignored -> {},
                err -> log.error("QuoteCloserEnrollmentJob tick failed", err));
    }

    /**
     * Visible-for-test entry — one full sweep across all tenants (enroll aged NEW quotes + exit
     * no-longer-open enrollments) and returns when done (the {@code CoverageNudgeJob.nudgeDueOnce} pattern).
     */
    public Mono<Void> sweepDueOnce() {
        return tenants.findAll()
                .concatMap(t -> sweepTenant(t.getId())
                        .onErrorResume(err -> {
                            log.warn("QuoteCloser sweep failed for tenant {}: {}",
                                    t.getId(), err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    private Mono<Void> sweepTenant(UUID tenantId) {
        TenantContext ctx = new TenantContext(tenantId, null, Set.of(SYSTEM_ROLE));
        return configs.findByTenantId(tenantId)
                .flatMap(config -> {
                    UUID campaignId = config.getCampaignId();
                    if (campaignId == null) {
                        // No campaign mapped → the enrollment leg is a clean no-op (still stop nothing).
                        log.debug("QuoteCloser: no campaign mapped for tenant {} — no enroll/stop", tenantId);
                        return Mono.empty();
                    }
                    return campaigns.findByTenantIdAndId(tenantId, campaignId)
                            .filter(NurtureCampaign::isActive)
                            .flatMap(campaign -> enrollAged(tenantId, campaign, config)
                                    .then(stopNoLongerOpen(tenantId, campaign)))
                            // Campaign missing / inactive → no enroll; the stop pass also needs the campaign.
                            .switchIfEmpty(Mono.fromRunnable(() -> log.debug(
                                    "QuoteCloser: campaign {} missing/inactive for tenant {} — no enroll/stop",
                                    campaignId, tenantId)));
                })
                .then()
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /** Enroll the contact of each NEW quote older than the window into the campaign (idempotent). */
    private Mono<Void> enrollAged(UUID tenantId, NurtureCampaign campaign, QuoteCloserConfig config) {
        Instant cutoff = clock.instant().minus(
                Math.max(0, config.getUnacceptedWindowHours()), ChronoUnit.HOURS);
        return quotes.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, QuoteStatus.NEW)
                .filter(q -> q.getContactId() != null)
                .filter(q -> q.getCreatedAt() != null && q.getCreatedAt().isBefore(cutoff))
                .concatMap(q -> enrollIfAbsent(tenantId, campaign, q)
                        .onErrorResume(err -> {
                            log.warn("QuoteCloser enroll failed for quote {} (tenant {}): {}",
                                    q.getId(), tenantId, err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    /**
     * E1 explicit-boolean find-or-enroll into the QuoteCloser campaign — probe for an existing enrollment,
     * and only if absent insert a fresh ENROLLED row (due immediately). The unique
     * {@code tenant_campaign_contact_idx} backstops a concurrent enroll race
     * ({@code DuplicateKeyException → no-op}). NEVER {@code switchIfEmpty(create)}.
     */
    private Mono<Void> enrollIfAbsent(UUID tenantId, NurtureCampaign campaign, QuoteRequest quote) {
        UUID contactId = quote.getContactId();
        Instant now = clock.instant();
        return enrollments.findByTenantIdAndCampaignIdAndContactId(tenantId, campaign.getId(), contactId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("QuoteCloser: contact {} already enrolled in campaign {} — no duplicate",
                                contactId, campaign.getId());
                        return Mono.empty();
                    }
                    NurtureEnrollment fresh = NurtureEnrollment.builder()
                            .tenantId(tenantId)
                            .campaignId(campaign.getId())
                            .contactId(contactId)
                            .bucket(BUCKET)
                            .currentStepIndex(0)
                            .status(NurtureEnrollmentStatus.ENROLLED)
                            .nextFireAt(now)
                            .appliedBackoffDays(0)
                            .enrolledAt(now)
                            .build();
                    return enrollments.save(fresh)
                            .doOnNext(saved -> events.publish(DomainEvent.of(
                                    DomainEventType.QUOTE_CLOSER_ENROLLED, tenantId, saved.getId(),
                                    enrollPayload(quote, campaign.getId()))))
                            .then()
                            .onErrorResume(DuplicateKeyException.class, e -> {
                                log.debug("QuoteCloser: concurrent enroll lost for contact {} campaign {} "
                                        + "— treated as already-enrolled", contactId, campaign.getId());
                                return Mono.empty();
                            });
                });
    }

    /**
     * Defense-in-depth stop: exit every active QuoteCloser-campaign enrollment whose contact no longer has
     * ANY {@code NEW} quote (it was ACCEPTED / DECLINED / BOOKED). The runner naturally stops touching an
     * {@code EXITED} enrollment. The prompt accept-time stop is the {@code QuoteWonSubscriber}; this catches
     * office-set DECLINED/BOOKED transitions that emit no event.
     */
    private Mono<Void> stopNoLongerOpen(UUID tenantId, NurtureCampaign campaign) {
        return enrollments.findAllByTenantIdAndCampaignId(tenantId, campaign.getId())
                .filter(enr -> enr.getStatus() != null && !enr.getStatus().isTerminal())
                .concatMap(enr -> contactHasOpenQuote(tenantId, enr.getContactId())
                        .flatMap(open -> open
                                ? Mono.empty()
                                : exit(enr, "quote no longer open"))
                        .onErrorResume(err -> {
                            log.warn("QuoteCloser stop check failed for enrollment {} (tenant {}): {}",
                                    enr.getId(), tenantId, err.toString());
                            return Mono.empty();
                        }))
                .then();
    }

    /** True iff this contact still has at least one {@code NEW} (un-accepted) quote. */
    private Mono<Boolean> contactHasOpenQuote(UUID tenantId, UUID contactId) {
        if (contactId == null) {
            return Mono.just(false);
        }
        return quotes.findByTenantIdAndStatusOrderByCreatedAtDesc(tenantId, QuoteStatus.NEW)
                .filter(q -> contactId.equals(q.getContactId()))
                .hasElements();
    }

    private Mono<Void> exit(NurtureEnrollment enr, String reason) {
        return enrollments.save(enr.toBuilder()
                        .status(NurtureEnrollmentStatus.EXITED)
                        .exitedReason(reason)
                        .nextFireAt(null)
                        .build())
                .doOnNext(saved -> log.debug("QuoteCloser: exited enrollment {} ({})", saved.getId(), reason))
                .then();
    }

    private static Map<String, Object> enrollPayload(QuoteRequest quote, UUID campaignId) {
        Map<String, Object> p = new HashMap<>();
        if (quote.getId() != null) p.put("quoteRequestId", quote.getId().toString());
        if (quote.getContactId() != null) p.put("contactId", quote.getContactId().toString());
        if (campaignId != null) p.put("campaignId", campaignId.toString());
        return p;
    }
}
