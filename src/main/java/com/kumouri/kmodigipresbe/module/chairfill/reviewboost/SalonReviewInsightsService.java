package com.kumouri.kmodigipresbe.module.chairfill.reviewboost;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.gbp.ReviewInsightsService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.response.ReviewInsights;
import com.kumouri.kmodigipresbe.module.salonspa.model.StaffMember;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * T6 Salon "ReviewBoost" — the per-stylist review-insights aggregator: the salon dashboard's
 * "which chair drives our reviews?" board. The one genuinely net-new read piece for ReviewBoost — almost
 * everything else (per-stylist attribution, the no-incentive request SMS, sentiment triage + manager
 * alert, AI reply drafts + the approval queue, per-subject insights) already ships in the E3 review engine
 * + the ChairFill flagship (CF-1..CF-5).
 *
 * <h2>Generalize, don't fork — reuses the shipped {@link ReviewInsightsService} verbatim</h2>
 * The board is assembled purely by calling the unchanged {@code ReviewInsightsService}:
 * <ul>
 *   <li>{@link ReviewInsightsService#insightsForTenant()} — once, for the tenant-wide review-content header
 *       (count / rating / sentiment) AND the tenant request-funnel rollup;</li>
 *   <li>{@link ReviewInsightsService#insightsForSubject(ReviewSubjectType, UUID)
 *       insightsForSubject(STAFF, staffMemberId)} — once per active {@link StaffMember}, for that stylist's
 *       request → response funnel.</li>
 * </ul>
 * The active stylists come from {@link StaffMemberRepository#findByTenantIdAndActive} (the salon-spa
 * directory). {@code ReviewInsightsService} (and every other E3 / salon core) stays <strong>empty-diff vs
 * {@code main}</strong> — this is a thin caller, not a seam.
 *
 * <h2>Attribution honesty (the {@code ReviewInsights} note, restated)</h2>
 * Google reviews carry no per-staff / per-job attribution, so the review-content block is inherently
 * tenant-level and is reported once in the board header. The request funnel is the genuinely
 * stylist-attributable part (each salon review-request is stamped {@code STAFF + staffMemberId} by the
 * shipped {@code ReviewRequestService} on {@code BOOKING_COMPLETED}), so it is reported per stylist.
 *
 * <p>Runs in the active (authenticated, ADMIN-gated) reactive context — the {@code ReviewInsightsService}
 * resolves the tenant from {@link TenantContextHolder}; the staff enumeration here uses the same tenant.
 */
@Slf4j
public class SalonReviewInsightsService {

    private final ReviewInsightsService reviewInsightsService;
    private final StaffMemberRepository staffMembers;
    private final IntegrationConnectionRepository connections;
    private final boolean senderEnabled;
    private final boolean sentimentRefineEnabled;
    private final boolean negativeAlertEnabled;

    public SalonReviewInsightsService(
            ReviewInsightsService reviewInsightsService,
            StaffMemberRepository staffMembers,
            IntegrationConnectionRepository connections,
            boolean senderEnabled,
            boolean sentimentRefineEnabled,
            boolean negativeAlertEnabled) {
        this.reviewInsightsService = reviewInsightsService;
        this.staffMembers = staffMembers;
        this.connections = connections;
        this.senderEnabled = senderEnabled;
        this.sentimentRefineEnabled = sentimentRefineEnabled;
        this.negativeAlertEnabled = negativeAlertEnabled;
    }

    /**
     * Builds the salon review board: the tenant-wide review-content + funnel header, plus one
     * {@link StylistReviewStatsDTO} per active stylist (its request → response funnel).
     */
    public Mono<SalonReviewBoardDTO> board() {
        return TenantContextHolder.required().flatMap(ctx -> {
            UUID tenantId = ctx.tenantId();
            Mono<ReviewInsights> tenantRollup = reviewInsightsService.insightsForTenant();
            Mono<List<StylistReviewStatsDTO>> stylistRows =
                    staffMembers.findByTenantIdAndActive(tenantId, true)
                            .concatMap(this::statsForStylist)
                            .collectList();
            return Mono.zip(tenantRollup, stylistRows)
                    .map(t -> assemble(t.getT1(), t.getT2()));
        });
    }

    /** Per-stylist funnel via the reused {@code insightsForSubject(STAFF, staffMemberId)}. */
    private Mono<StylistReviewStatsDTO> statsForStylist(StaffMember stylist) {
        return reviewInsightsService.insightsForSubject(ReviewSubjectType.STAFF, stylist.getId())
                .map(in -> new StylistReviewStatsDTO(
                        stylist.getId(),
                        stylist.getDisplayName(),
                        in.requestsSent(),
                        in.requestsResponded(),
                        in.responseRate()));
    }

    private SalonReviewBoardDTO assemble(ReviewInsights tenant, List<StylistReviewStatsDTO> stylists) {
        return new SalonReviewBoardDTO(
                tenant.reviewCount(),
                tenant.averageRating(),
                tenant.positiveCount(),
                tenant.neutralCount(),
                tenant.negativeCount(),
                tenant.unclassifiedCount(),
                tenant.requestsSent(),
                tenant.requestsResponded(),
                tenant.responseRate(),
                new ArrayList<>(stylists));
    }

    /**
     * Read-back of the salon's ReviewBoost wiring: the per-tenant Google review link (from the Twilio
     * {@link IntegrationConnection} {@code config["reviewLink"]} the E3 sender reads) + the effective
     * default-OFF sender / sentiment-refine / negative-alert flags. Pure read; no new config store.
     */
    public Mono<ReviewBoostConfigDTO> config() {
        return TenantContextHolder.required().flatMap(ctx ->
                connections.findByTenantIdAndProvider(ctx.tenantId(), TwilioSmsService.PROVIDER)
                        // mapNotNull (not map) — a missing reviewLink must complete-empty, not emit null
                        // into the reactive chain (Reactor forbids null). The reused E3 pattern.
                        .mapNotNull(conn -> conn.getConfig() == null ? null : conn.getConfig().get("reviewLink"))
                        .map(this::toConfig)
                        // No Twilio connection, or a connection with no reviewLink → not configured.
                        .switchIfEmpty(Mono.fromSupplier(() -> toConfig(null))));
    }

    private ReviewBoostConfigDTO toConfig(String reviewLink) {
        boolean configured = reviewLink != null && !reviewLink.isBlank();
        return new ReviewBoostConfigDTO(
                configured,
                configured ? reviewLink : null,
                senderEnabled,
                sentimentRefineEnabled,
                negativeAlertEnabled);
    }
}
