package com.kumouri.kmodigipresbe.controller.nurture;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.extension.TenantModuleRegistry;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCampaign;
import com.kumouri.kmodigipresbe.model.nurture.NurtureSegmentDefinition;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureReplyService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Admin surface for the Nurture / Cadence Engine (E1) — campaign CRUD, trigger-segmentation-and-enroll,
 * analytics read, and a manual positive-reply mark (the same service the E2 inbound-SMS path will call).
 *
 * <h2>Gating (the {@code WaitlistBoardController} / {@code GbpReviewReplyAdminController} precedent)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.nurture", name="enabled",
 *       matchIfMissing=true)} — present by default; absent from the OpenAPI spec only when the module
 *       is explicitly disabled (so it 404s, the module-gate precedent).</li>
 *   <li>per-tenant membership via {@link TenantModuleRegistry#requireEnabled(String)} (1130/1132).</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise).</li>
 * </ul>
 * Base path {@code /api/v1} (via {@code spring.webflux.base-path}), so these map to
 * {@code /api/v1/nurture/campaigns...}.
 *
 * <h2>Idempotency</h2>
 * {@code @IdempotentRoute} on the three side-effecting POSTs (create, segment-and-enroll,
 * positive-reply) — each returns a non-empty body (the {@code @IdempotentRoute} response-tee
 * requirement; the {@code spawn-now} lesson). List/get/analytics/update/delete are not idempotency-keyed.
 */
@RestController
@RequestMapping("/nurture/campaigns")
@ConditionalOnProperty(prefix = "kmosf.modules.nurture", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class NurtureCampaignController {

    private final NurtureCampaignRepository campaigns;
    private final NurtureSegmentationService segmentation;
    private final NurtureAnalyticsService analytics;
    private final NurtureReplyService replyService;
    private final TenantModuleRegistry modules;

    /** Create a campaign (validates ≥1 step contiguous + each step's channel template; ≥1 segment). */
    @PostMapping
    @IdempotentRoute
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<NurtureCampaign> create(@RequestBody CampaignRequest body) {
        return guard().then(TenantContextHolder.required()).flatMap(ctx -> {
            NurtureCampaign campaign = body.toNewEntity(ctx.tenantId());
            validate(campaign);
            return campaigns.save(campaign);
        });
    }

    /** List the tenant's campaigns. */
    @GetMapping
    public Flux<NurtureCampaign> list() {
        return guard().thenMany(TenantContextHolder.required()
                .flatMapMany(ctx -> campaigns.findAllByTenantId(ctx.tenantId())));
    }

    /** Get one campaign (4301 if not found). */
    @GetMapping("/{id}")
    public Mono<NurtureCampaign> get(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> campaigns.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> notFound())));
    }

    /** Update a campaign in place (replaces name/description/active/segments/steps/cap). */
    @PutMapping("/{id}")
    public Mono<NurtureCampaign> update(@PathVariable UUID id, @RequestBody CampaignRequest body) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> campaigns.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> notFound()))
                        .flatMap(existing -> {
                            NurtureCampaign merged = body.applyTo(existing);
                            validate(merged);
                            return campaigns.save(merged);
                        }));
    }

    /** Soft "delete" by deactivating? No — a hard delete of the definition (enrollments persist). */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> delete(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> campaigns.findByTenantIdAndId(ctx.tenantId(), id)
                        .switchIfEmpty(Mono.error(() -> notFound()))
                        .flatMap(campaigns::delete));
    }

    /** Trigger segmentation + enroll for this campaign; returns the per-run counts. */
    @PostMapping("/{id}/segment-and-enroll")
    @IdempotentRoute
    public Mono<NurtureSegmentationService.SegmentationResult> segmentAndEnroll(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> segmentation.segmentAndEnroll(ctx.tenantId(), id));
    }

    /** Per-campaign + per-segment funnel analytics. */
    @GetMapping("/{id}/analytics")
    public Mono<NurtureAnalyticsService.NurtureCampaignAnalytics> analytics(@PathVariable UUID id) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> analytics.summarize(ctx.tenantId(), id));
    }

    /**
     * Mark a positive reply for an enrollment (manual / admin; the E2 inbound-SMS path calls the same
     * service). Exits the enrollment + best-effort sends the booking link. Returns the updated
     * enrollment.
     */
    @PostMapping("/enrollments/{enrollmentId}/positive-reply")
    @IdempotentRoute
    public Mono<com.kumouri.kmodigipresbe.model.nurture.NurtureEnrollment> positiveReply(
            @PathVariable UUID enrollmentId) {
        return guard().then(TenantContextHolder.required())
                .flatMap(ctx -> replyService.handlePositiveReplyByEnrollment(
                        ctx.tenantId(), enrollmentId));
    }

    /** nurture module loaded + enabled for the tenant, then ADMIN. */
    private Mono<Void> guard() {
        return modules.requireEnabled(NurtureAutoConfiguration.MODULE_KEY)
                .then(RoleGuard.requireRole("ADMIN"));
    }

    private static DigiPresBeException notFound() {
        return new DigiPresBeException("Nurture campaign not found", 4301, 404);
    }

    /** Validate the campaign definition (4303): ≥1 segment, ≥1 step, contiguous 0-based step indexes,
     *  each step carries its channel's required template fields. */
    private static void validate(NurtureCampaign campaign) {
        if (campaign.getSegments() == null || campaign.getSegments().isEmpty()) {
            throw new DigiPresBeException(
                    "Nurture campaign requires at least one segment definition", 4303, 400);
        }
        List<NurtureCadenceStep> steps = campaign.getSteps();
        if (steps == null || steps.isEmpty()) {
            throw new DigiPresBeException(
                    "Nurture campaign requires at least one cadence step", 4303, 400);
        }
        for (int i = 0; i < steps.size(); i++) {
            NurtureCadenceStep s = steps.get(i);
            if (s.stepIndex() != i) {
                throw new DigiPresBeException(
                        "Nurture cadence step indexes must be contiguous 0-based (expected " + i
                                + " at position " + i + ", got " + s.stepIndex() + ")", 4303, 400);
            }
            if (s.channel() == null || !s.hasRequiredTemplate()) {
                throw new DigiPresBeException(
                        "Nurture cadence step " + i + " is missing its " + s.channel()
                                + " template fields", 4303, 400);
            }
        }
    }

    /**
     * The campaign create/update request body — drops server-managed fields (id/tenantId/version/
     * timestamps); segments + steps reuse the embedded records directly.
     *
     * @param name                          campaign name (unique per tenant)
     * @param description                   optional description
     * @param active                        active flag (defaults true on create when null)
     * @param segments                      ordered dormancy-segment rules
     * @param steps                         ordered cadence steps
     * @param maxTouchesPerContactPerWindow optional TCPA cap (defaults to 2 on create when null)
     */
    public record CampaignRequest(
            String name,
            String description,
            Boolean active,
            List<NurtureSegmentDefinition> segments,
            List<NurtureCadenceStep> steps,
            Integer maxTouchesPerContactPerWindow) {

        NurtureCampaign toNewEntity(UUID tenantId) {
            return NurtureCampaign.builder()
                    .tenantId(tenantId)
                    .name(name)
                    .description(description)
                    .active(active == null || active)
                    .segments(segments == null ? new ArrayList<>() : new ArrayList<>(segments))
                    .steps(steps == null ? new ArrayList<>() : new ArrayList<>(steps))
                    .maxTouchesPerContactPerWindow(
                            maxTouchesPerContactPerWindow == null ? 2 : maxTouchesPerContactPerWindow)
                    .build();
        }

        NurtureCampaign applyTo(NurtureCampaign existing) {
            return existing.toBuilder()
                    .name(name != null ? name : existing.getName())
                    .description(description)
                    .active(active == null ? existing.isActive() : active)
                    .segments(segments == null ? new ArrayList<>() : new ArrayList<>(segments))
                    .steps(steps == null ? new ArrayList<>() : new ArrayList<>(steps))
                    .maxTouchesPerContactPerWindow(
                            maxTouchesPerContactPerWindow == null
                                    ? existing.getMaxTouchesPerContactPerWindow()
                                    : maxTouchesPerContactPerWindow)
                    .build();
        }
    }
}
