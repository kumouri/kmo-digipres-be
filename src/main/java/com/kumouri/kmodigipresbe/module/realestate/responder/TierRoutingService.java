package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.nurture.DormancyBucket;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment;
import com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollmentStatus;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.concierge.QualificationService;
import com.kumouri.kmodigipresbe.module.responder.ResponderAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — <strong>tier routing on qualification</strong>: the headline
 * routing layer. A dedicated {@code @PostConstruct} subscriber on {@link DomainEventType#LEAD_SCORE_UPDATED}
 * (the UNCHANGED nightly {@code LeadScoringV2Service} stamp), a <strong>structural mirror of the RE-2
 * {@code LeadHandoffService}</strong> ({@code events.stream().filter(type).flatMap(handle)} + synthetic
 * {@link TenantContext} + a visible-for-test {@link #handle(DomainEvent)}).
 *
 * <h2>One handler per tier (no overlap with the hot-handoff)</h2>
 * <ul>
 *   <li><strong>HOT</strong> → owned by the RE-2 {@code LeadHandoffService} (the agent hot-handoff +
 *       booking offer). <strong>This service no-ops on HOT</strong> — so HOT fires exactly the existing
 *       handoff and T3 never double-routes it.</li>
 *   <li><strong>WARM</strong> → auto-enroll the buyer Contact into the tenant's configured
 *       {@link MidnightResponderConfig#getWarmCampaignId() warm} nurture campaign.</li>
 *   <li><strong>COLD</strong> → auto-enroll into the tenant's configured
 *       {@link MidnightResponderConfig#getColdCampaignId() cold} long-cadence campaign.</li>
 * </ul>
 * A null tier→campaign mapping (or no config row) ⇒ that tier routes nowhere — a clean no-op.
 *
 * <h2>Scoping (defense-in-depth — zero blast radius)</h2>
 * <ul>
 *   <li>module-gated: the bean exists only when BOTH realestate + responder are loaded, and {@link #process}
 *       re-checks {@code Tenant.enabledModules} contains BOTH keys (a non-realestate / non-responder
 *       {@code LEAD_SCORE_UPDATED} is a hard no-op);</li>
 *   <li>only for a contact with a <strong>concierge-sourced realestate Deal</strong>
 *       ({@code customFields.source == "concierge"} — {@link QualificationService#isConciergeSourced}),
 *       the same recognition key the RE-2 hot-handoff uses;</li>
 *   <li>only for a WARM or COLD tier (HOT excluded, above).</li>
 * </ul>
 *
 * <h2>Idempotent + best-effort</h2>
 * The enroll is the E1 <strong>explicit-boolean</strong> find-or-enroll over the unique
 * {@code NurtureEnrollment.tenant_campaign_contact_idx}
 * ({@code findBy…CampaignIdAndContactId(...).map(true).defaultIfEmpty(false)} then a ledger-style insert
 * with {@code onErrorResume(DuplicateKeyException → false)}) — <strong>NEVER {@code switchIfEmpty(create)}</strong>.
 * A re-fired {@code LEAD_SCORE_UPDATED} (nightly re-score, restart, concurrent emit) does ZERO duplicate
 * enroll. The whole chain is wrapped {@code onErrorResume} (advisory {@code 4382} on a missing/inactive
 * campaign) so a config gap never corrupts a Deal and never 500s the webhook path. Hand-constructed as a
 * {@code @Bean} by {@code RealEstateMidnightAutoConfiguration}; the {@code @PostConstruct} fires the bus
 * subscription at init.
 */
@Slf4j
public class TierRoutingService {

    private final DomainEventPublisher events;
    private final TenantRepository tenants;
    private final DealRepository deals;
    private final MidnightResponderConfigRepository configs;
    private final NurtureCampaignRepository campaigns;
    private final NurtureEnrollmentRepository enrollments;

    public TierRoutingService(DomainEventPublisher events,
                              TenantRepository tenants,
                              DealRepository deals,
                              MidnightResponderConfigRepository configs,
                              NurtureCampaignRepository campaigns,
                              NurtureEnrollmentRepository enrollments) {
        this.events = events;
        this.tenants = tenants;
        this.deals = deals;
        this.configs = configs;
        this.campaigns = campaigns;
        this.enrollments = enrollments;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.LEAD_SCORE_UPDATED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("T3 TierRoutingService: error processing LEAD_SCORE_UPDATED "
                                    + "for tenant {}", e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code LEAD_SCORE_UPDATED} event end-to-end and return when
     * done (so an IT can drive it deterministically without the live event bus + a sleep). Resolves the
     * contact id from the event subject, re-reads the tier off the event payload, and runs the tier route
     * under a synthetic context. HOT (and a missing tenant/contact) is a clean no-op.
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        UUID contactId = resolveContactId(event);
        String tier = resolveTier(event);
        if (tenantId == null || contactId == null || tier == null) {
            return Mono.empty();
        }
        // HOT is owned by the RE-2 hot-handoff — T3 routes only WARM / COLD. (One handler per tier.)
        if (LeadScore.TIER_HOT.equals(tier)) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return process(tenantId, contactId, tier)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private static UUID resolveContactId(DomainEvent event) {
        if (event.subjectId() != null) {
            return event.subjectId();
        }
        Object raw = event.payload() == null ? null : event.payload().get("contactId");
        if (raw instanceof UUID u) {
            return u;
        }
        if (raw != null) {
            try {
                return UUID.fromString(raw.toString());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static String resolveTier(DomainEvent event) {
        Object raw = event.payload() == null ? null : event.payload().get("tier");
        return raw == null ? null : raw.toString();
    }

    /**
     * Defense-in-depth (zero blast radius): re-check the tenant has BOTH the realestate AND responder
     * modules, then resolve the WARM/COLD campaign for this tier from the per-tenant config, find the
     * contact's concierge-sourced realestate Deal, and enroll (idempotent). A non-realestate /
     * non-responder / non-concierge / unmapped-tier update is a clean no-op.
     */
    private Mono<Void> process(UUID tenantId, UUID contactId, String tier) {
        return tenants.findById(tenantId)
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(RealEstateAutoConfiguration.MODULE_KEY)
                        && t.getEnabledModules().contains(ResponderAutoConfiguration.MODULE_KEY))
                .flatMap(t -> configs.findByTenantId(tenantId))
                .flatMap(config -> {
                    UUID campaignId = campaignForTier(config, tier);
                    DormancyBucket bucket = bucketForTier(tier);
                    if (campaignId == null || bucket == null) {
                        // No mapping for this tier (or an unknown tier) → clean no-op.
                        log.debug("T3 tier-route: no campaign mapped for tier {} (tenant {}) — no enroll",
                                tier, tenantId);
                        return Mono.empty();
                    }
                    return findConciergeDeal(tenantId, contactId)
                            .flatMap(deal -> enroll(tenantId, contactId, deal, campaignId, bucket, tier));
                })
                .then();
    }

    /** The configured campaign for a tier (WARM → warm, COLD → cold; HOT already excluded), or null. */
    private static UUID campaignForTier(MidnightResponderConfig config, String tier) {
        if (config == null) {
            return null;
        }
        if (LeadScore.TIER_WARM.equals(tier)) {
            return config.getWarmCampaignId();
        }
        if (LeadScore.TIER_COLD.equals(tier)) {
            return config.getColdCampaignId();
        }
        return null;
    }

    /** The dormancy bucket label recorded on the enrollment for a tier (B for WARM, C for COLD). */
    private static DormancyBucket bucketForTier(String tier) {
        if (LeadScore.TIER_WARM.equals(tier)) {
            return DormancyBucket.B;
        }
        if (LeadScore.TIER_COLD.equals(tier)) {
            return DormancyBucket.C;
        }
        return null;
    }

    /** The contact's first concierge-sourced realestate Deal (the tier-route target), or empty. */
    private Mono<Deal> findConciergeDeal(UUID tenantId, UUID contactId) {
        return deals.findAllByTenantId(tenantId)
                .filter(d -> contactId.equals(d.getPrimaryContactId()))
                .filter(QualificationService::isConciergeSourced)
                .next();
    }

    /**
     * E1 explicit-boolean find-or-enroll into the tier's campaign — probe for an existing enrollment, and
     * only if absent insert a fresh ENROLLED row (due immediately). The unique
     * {@code tenant_campaign_contact_idx} backstops a concurrent enroll race
     * ({@code DuplicateKeyException → no-op}). NEVER {@code switchIfEmpty(create)}. Best-effort: a missing /
     * inactive campaign logs advisory {@code 4382} and no-ops.
     */
    private Mono<Void> enroll(UUID tenantId, UUID contactId, Deal deal, UUID campaignId,
                              DormancyBucket bucket, String tier) {
        return campaigns.findByTenantIdAndId(tenantId, campaignId)
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("T3 tier-route: campaign {} not found for tenant {} (advisory 4382) — no enroll",
                            campaignId, tenantId);
                    return Mono.empty();
                }))
                .filter(NurtureCampaign::isActive)
                .flatMap(campaign -> enrollIfAbsent(tenantId, contactId, deal, campaign, bucket, tier))
                .switchIfEmpty(Mono.fromRunnable(() -> log.debug(
                        "T3 tier-route: campaign {} inactive for tenant {} (advisory 4382) — no enroll",
                        campaignId, tenantId)))
                .onErrorResume(err -> {
                    log.warn("T3 tier-route: enroll into campaign {} failed (best-effort) for contact {}: {}",
                            campaignId, contactId, err.toString());
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> enrollIfAbsent(UUID tenantId, UUID contactId, Deal deal, NurtureCampaign campaign,
                                      DormancyBucket bucket, String tier) {
        Instant now = Instant.now();
        return enrollments.findByTenantIdAndCampaignIdAndContactId(tenantId, campaign.getId(), contactId)
                .map(e -> true)
                .defaultIfEmpty(false)
                .flatMap(exists -> {
                    if (exists) {
                        log.debug("T3 tier-route: contact {} already enrolled in campaign {} — no duplicate",
                                contactId, campaign.getId());
                        return Mono.empty();
                    }
                    NurtureEnrollment fresh = NurtureEnrollment.builder()
                            .tenantId(tenantId)
                            .campaignId(campaign.getId())
                            .contactId(contactId)
                            .bucket(bucket)
                            .currentStepIndex(0)
                            .status(NurtureEnrollmentStatus.ENROLLED)
                            .nextFireAt(now)
                            .appliedBackoffDays(0)
                            .enrolledAt(now)
                            .build();
                    return enrollments.save(fresh)
                            .doOnNext(saved -> events.publish(DomainEvent.of(
                                    DomainEventType.CONCIERGE_TIER_ROUTED, tenantId, contactId,
                                    routePayload(contactId, deal, campaign.getId(), tier))))
                            .then()
                            .onErrorResume(DuplicateKeyException.class, e -> {
                                log.debug("T3 tier-route: concurrent enroll lost for contact {} campaign {} "
                                        + "— treated as already-enrolled", contactId, campaign.getId());
                                return Mono.empty();
                            });
                });
    }

    private static Map<String, Object> routePayload(UUID contactId, Deal deal, UUID campaignId, String tier) {
        Map<String, Object> p = new HashMap<>();
        p.put("contactId", contactId == null ? null : contactId.toString());
        p.put("dealId", deal == null || deal.getId() == null ? null : deal.getId().toString());
        p.put("tier", tier);
        p.put("campaignId", campaignId == null ? null : campaignId.toString());
        p.put("enrolled", true);
        return p;
    }
}
