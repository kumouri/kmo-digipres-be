package com.kumouri.kmodigipresbe.service.nurture;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.deal.PipelineStage;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Segments a CRM's dormant contacts into {@link DormancyBucket}s and enrolls the matched ones into a
 * {@link NurtureCampaign} (E1 — Nurture / Cadence Engine).
 *
 * <h2>Vertical-agnostic by construction</h2>
 * The day-windows (and optional WON-deal lifetime-value bands) that bucket a contact live in the
 * campaign's {@link NurtureSegmentDefinition} list — never in this code. A contact is assigned the
 * <em>first</em> matching segment in list order. So the same service reactivates Real Estate, Health,
 * and Home cohorts purely from the seeded campaign document.
 *
 * <h2>§9 invariants</h2>
 * <ul>
 *   <li><strong>Find-or-enroll is explicit-boolean</strong>
 *       ({@code findBy…CampaignIdAndContactId(...).map(e->true).defaultIfEmpty(false)}) over a
 *       ledger-style unique-index insert with {@code onErrorResume(DuplicateKeyException → empty)} —
 *       NEVER {@code switchIfEmpty(create)}. An already-enrolled contact is left untouched.</li>
 *   <li>{@code switchIfEmpty} is used only for the genuine campaign-not-found (4301).</li>
 *   <li><strong>TCPA:</strong> a contact carrying the {@code sms-opt-out} tag is skipped entirely (no
 *       enrollment) — honored again at send time by the runner.</li>
 * </ul>
 * Runs under the caller's tenant context (the admin controller resolves it); all repo finders carry an
 * explicit {@code tenantId}.
 */
@Slf4j
public class NurtureSegmentationService {

    private final NurtureCampaignRepository campaigns;
    private final NurtureEnrollmentRepository enrollments;
    private final ContactRepository contacts;
    private final ActivityRepository activities;
    private final DealRepository deals;
    private final DomainEventPublisher events;
    private final Clock clock;

    /**
     * Explicit constructor (not Lombok {@code @RequiredArgsConstructor}) so the {@link Clock} resolves
     * via {@link ObjectProvider} with a {@code systemUTC} fallback — the codebase has no global
     * {@code Clock} bean (the {@code RecurringInvoiceSpawnService} precedent). A test that registers a
     * fixed {@code Clock} bean overrides it.
     */
    public NurtureSegmentationService(NurtureCampaignRepository campaigns,
                                      NurtureEnrollmentRepository enrollments,
                                      ContactRepository contacts,
                                      ActivityRepository activities,
                                      DealRepository deals,
                                      DomainEventPublisher events,
                                      ObjectProvider<Clock> clockProvider) {
        this.campaigns = campaigns;
        this.enrollments = enrollments;
        this.contacts = contacts;
        this.activities = activities;
        this.deals = deals;
        this.events = events;
        this.clock = clockProvider.getIfAvailable(Clock::systemUTC);
    }

    /**
     * Segments the tenant's contacts against the campaign's rules and enrolls fresh matches.
     *
     * @param tenantId   the tenant (the resolved request tenant)
     * @param campaignId the campaign to segment + enroll into
     * @return the per-run counts
     */
    public Mono<SegmentationResult> segmentAndEnroll(UUID tenantId, UUID campaignId) {
        return campaigns.findByTenantIdAndId(tenantId, campaignId)
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Nurture campaign not found", 4301, 404)))
                .flatMap(campaign -> {
                    if (!campaign.isActive()) {
                        return Mono.error(new DigiPresBeException(
                                "Nurture campaign is inactive — cannot segment/enroll", 4302, 409));
                    }
                    if (campaign.getSegments() == null || campaign.getSegments().isEmpty()) {
                        return Mono.error(new DigiPresBeException(
                                "Nurture campaign has no segment definitions", 4303, 400));
                    }
                    return runSegmentation(tenantId, campaign);
                });
    }

    private Mono<SegmentationResult> runSegmentation(UUID tenantId, NurtureCampaign campaign) {
        AtomicInteger evaluated = new AtomicInteger();
        AtomicInteger matched = new AtomicInteger();
        AtomicInteger enrolled = new AtomicInteger();
        AtomicInteger skippedOptedOut = new AtomicInteger();
        AtomicInteger alreadyEnrolled = new AtomicInteger();
        Instant now = clock.instant();
        boolean needsValue = campaign.getSegments().stream()
                .anyMatch(NurtureSegmentDefinition::hasValueBand);

        return contacts.findAllByTenantId(tenantId)
                .concatMap(contact -> {
                    evaluated.incrementAndGet();
                    if (isOptedOut(contact)) {
                        skippedOptedOut.incrementAndGet();
                        return Mono.empty();
                    }
                    return daysSinceLastActivity(tenantId, contact, now)
                            .flatMap(days -> lifetimeValueIfNeeded(tenantId, contact, needsValue)
                                    .flatMap(value -> {
                                        DormancyBucket bucket = matchBucket(campaign, days, value);
                                        if (bucket == null) {
                                            return Mono.empty();
                                        }
                                        matched.incrementAndGet();
                                        return enrollIfAbsent(tenantId, campaign, contact, bucket, now)
                                                .doOnNext(created -> {
                                                    if (created) {
                                                        enrolled.incrementAndGet();
                                                    } else {
                                                        alreadyEnrolled.incrementAndGet();
                                                    }
                                                });
                                    }))
                            .then();
                })
                .then(Mono.fromSupplier(() -> new SegmentationResult(
                        campaign.getId(),
                        evaluated.get(), matched.get(), enrolled.get(),
                        skippedOptedOut.get(), alreadyEnrolled.get())));
    }

    /** First-matching segment (day window AND optional value band), or null if no rule matches. */
    private DormancyBucket matchBucket(NurtureCampaign campaign, long daysDormant, BigDecimal value) {
        for (NurtureSegmentDefinition seg : campaign.getSegments()) {
            if (seg.matchesDays(daysDormant) && (!seg.hasValueBand() || seg.matchesValue(value))) {
                return seg.bucket();
            }
        }
        return null;
    }

    /**
     * Explicit-boolean find-or-enroll: probe for an existing enrollment, and only if absent insert a
     * fresh ENROLLED row (due immediately). The unique {@code tenant_campaign_contact_idx} backstops a
     * concurrent enroll race ({@code DuplicateKeyException → false} = treated as already-enrolled).
     * NEVER {@code switchIfEmpty(create)}.
     *
     * @return true if a new enrollment was created, false if one already existed
     */
    private Mono<Boolean> enrollIfAbsent(UUID tenantId, NurtureCampaign campaign, Contact contact,
                                         DormancyBucket bucket, Instant now) {
        return enrollments.findByTenantIdAndCampaignIdAndContactId(
                        tenantId, campaign.getId(), contact.getId())
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.just(false);
                    }
                    NurtureEnrollment fresh = NurtureEnrollment.builder()
                            .tenantId(tenantId)
                            .campaignId(campaign.getId())
                            .contactId(contact.getId())
                            .bucket(bucket)
                            .currentStepIndex(0)
                            .status(NurtureEnrollmentStatus.ENROLLED)
                            .nextFireAt(now)
                            .appliedBackoffDays(0)
                            .enrolledAt(now)
                            .build();
                    return enrollments.save(fresh)
                            .doOnNext(saved -> events.publish(DomainEvent.of(
                                    DomainEventType.NURTURE_CONTACT_ENROLLED, tenantId, saved.getId(),
                                    java.util.Map.of(
                                            "campaignId", campaign.getId().toString(),
                                            "contactId", contact.getId().toString(),
                                            "bucket", bucket.name()))))
                            .thenReturn(true)
                            .onErrorResume(DuplicateKeyException.class, e -> {
                                log.debug("Nurture concurrent-enroll lost for contact {} campaign {} "
                                        + "— treated as already-enrolled", contact.getId(),
                                        campaign.getId());
                                return Mono.just(false);
                            });
                });
    }

    /**
     * Days since the contact's most-recent {@code Activity(subjectType=CONTACT)}; falls back to the
     * contact's {@code updatedAt} (then {@code createdAt}) when it has no activities — never NPE, and a
     * brand-new contact reads as ~0 days dormant (so a "very dormant" segment never sweeps it in).
     */
    private Mono<Long> daysSinceLastActivity(UUID tenantId, Contact contact, Instant now) {
        return activities.findTopByTenantIdAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                        tenantId, SubjectType.CONTACT, contact.getId())
                .mapNotNull(Activity::getOccurredAt)
                .switchIfEmpty(Mono.justOrEmpty(fallbackLastSeen(contact)))
                .map(last -> daysBetween(last, now))
                .defaultIfEmpty(0L);
    }

    private static Instant fallbackLastSeen(Contact contact) {
        if (contact.getUpdatedAt() != null) {
            return contact.getUpdatedAt();
        }
        return contact.getCreatedAt();
    }

    private static long daysBetween(Instant from, Instant to) {
        if (from == null || to == null || to.isBefore(from)) {
            return 0L;
        }
        return Duration.between(from, to).toDays();
    }

    /** Σ value of the contact's WON deals — only computed when a segment defines a value band. */
    private Mono<BigDecimal> lifetimeValueIfNeeded(UUID tenantId, Contact contact, boolean needed) {
        if (!needed) {
            return Mono.just(BigDecimal.ZERO);
        }
        return deals.findAllByTenantIdAndStage(tenantId, PipelineStage.WON)
                .filter(d -> contact.getId().equals(d.getPrimaryContactId()))
                .map(Deal::getValue)
                .filter(v -> v != null)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static boolean isOptedOut(Contact contact) {
        Set<String> tags = contact.getTags();
        return tags != null && tags.contains(RiskTieredPreventionService.SMS_OPT_OUT_TAG);
    }

    /**
     * Per-run segmentation counts.
     *
     * @param campaignId      the campaign segmented
     * @param evaluated       contacts examined
     * @param matched         contacts that fell into a segment
     * @param enrolled        fresh enrollments created
     * @param skippedOptedOut contacts skipped for carrying the {@code sms-opt-out} tag
     * @param alreadyEnrolled matched contacts that already had an enrollment (left untouched)
     */
    public record SegmentationResult(
            UUID campaignId,
            int evaluated,
            int matched,
            int enrolled,
            int skippedOptedOut,
            int alreadyEnrolled) {
    }
}
