package com.kumouri.kmodigipresbe.integration.moletripwire;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified claims of a mole-tripwire token (Phase 3 — NMM coverage-window automation, the B2
 * re-activity tripwire). Unlike the Phase-2 {@link com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken}
 * (which carries only {@code tenantId|widgetType|expiresAt}), a tripwire token also carries the
 * <strong>{@code projectId}</strong> of the coverage customer's {@code Project}, so a high-confidence
 * mole report can auto-create a re-treatment {@code Milestone} on exactly that Project — resolved
 * from the TOKEN only, never the request payload.
 *
 * <p>Issued + verified by {@link MoleTripwireTokenService} (a new additive sibling of
 * {@code PublicWidgetTokenService}; that reused core stays empty-diff — its 3-field token has no
 * slot for a Project id). The {@code widgetType} is always {@code "mole-tripwire"}.
 *
 * @param tenantId  the issuing tenant id (every effect runs under this tenant)
 * @param widgetType the token type claim — must be {@code "mole-tripwire"} (4013 on mismatch)
 * @param projectId the coverage customer's Project id the re-treatment Milestone is created on
 * @param expiresAt the token expiry instant
 */
public record MoleTripwireToken(UUID tenantId, String widgetType, UUID projectId, Instant expiresAt) {
}
