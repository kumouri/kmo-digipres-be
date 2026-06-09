package com.kumouri.kmodigipresbe.controller.integration;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.integration.gbp.ReviewInsightsService;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.response.ReviewInsights;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.Locale;
import java.util.UUID;

/**
 * ADMIN read surface for the E3 Review Engine per-entity insights (Review Engine — per-entity insights).
 * Returns the generic {@link ReviewInsights} rollup for the whole tenant or a single
 * {@code (subjectType, subjectId)} entity — review count, average rating, sentiment breakdown, and the
 * request→response funnel.
 *
 * <h2>Gating</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(prefix="kmosf.modules.gbp-reviews", name="enabled",
 *       matchIfMissing=true)} — the always-registerable admin surface, decoupled from the default-OFF
 *       poller / sender (the {@code GbpReviewReplyAdminController} precedent: a disabled module → not
 *       registered → 404). NOTE: this is intentionally NOT {@code TenantModuleRegistry.requireEnabled
 *       ("gbp-reviews")} — {@code gbp-reviews} is an {@code integration/} package, not a registered
 *       {@code ModuleDefinition}, so {@code requireEnabled} would 1130 (E3 deviation D1).</li>
 *   <li>{@link RoleGuard#requireRole "ADMIN"} on every endpoint (1800 otherwise).</li>
 * </ul>
 *
 * <p>Base path {@code /api/v1} (via {@code spring.webflux.base-path}), so these map to
 * {@code GET /api/v1/gbp/review-insights...}.
 */
@RestController
@RequestMapping("/gbp/review-insights")
@ConditionalOnProperty(prefix = "kmosf.modules.gbp-reviews", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class ReviewInsightsController {

    private final ReviewInsightsService service;

    /** The whole-tenant review insights rollup. ADMIN-gated. */
    @GetMapping
    public Mono<ReviewInsights> tenantInsights() {
        return RoleGuard.requireRole("ADMIN").then(service.insightsForTenant());
    }

    /**
     * The per-entity insights rollup for {@code (subjectType, subjectId)} — salon attributes to a
     * stylist ({@code STAFF}), home to a job ({@code PROJECT}). ADMIN-gated.
     *
     * @param subjectType the attribution dimension ({@code STAFF}/{@code PROJECT}/{@code OTHER},
     *                    case-insensitive); an unparseable value → {@code 4340}/400
     * @param subjectId   the attributed entity id
     */
    @GetMapping("/{subjectType}/{subjectId}")
    public Mono<ReviewInsights> subjectInsights(@PathVariable String subjectType,
                                                @PathVariable UUID subjectId) {
        ReviewSubjectType parsed = parseSubjectType(subjectType);
        return RoleGuard.requireRole("ADMIN").then(service.insightsForSubject(parsed, subjectId));
    }

    private static ReviewSubjectType parseSubjectType(String raw) {
        if (raw != null) {
            try {
                return ReviewSubjectType.valueOf(raw.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // fall through to the 4340 below
            }
        }
        throw new DigiPresBeException(
                "Unknown review subject type '" + raw + "' (expected STAFF|PROJECT|OTHER)", 4340, 400);
    }
}
