package com.kumouri.kmodigipresbe.integration.equipmentvision;

import java.time.Instant;
import java.util.UUID;

/**
 * Verified claims of an equipment-photo upload token (HS-2 — Home Services "Front Desk That Never
 * Sleeps"). The exact entity-bound shape of the Phase-3
 * {@link com.kumouri.kmodigipresbe.integration.moletripwire.MoleTripwireToken}, but the bound entity
 * is the DRAFT {@code WorkOrder} a home-services voicemail just created (HS-1) rather than a coverage
 * {@code Project}: a caller's equipment-nameplate photo is correlated to <em>their</em> work order by
 * the {@code workOrderId} claim — resolved from the TOKEN only, never the request payload — so there
 * is no inbound-MMS webhook and no 10DLC surface (plan §3 design fork).
 *
 * <p>Issued + verified by {@link EquipmentPhotoTokenService} (a new additive sibling of
 * {@code MoleTripwireTokenService} / {@code PublicWidgetTokenService}; those reused cores stay
 * empty-diff — their tokens have no slot for a WorkOrder id). The {@code widgetType} is always
 * {@code "equipment-photo"}.
 *
 * @param tenantId    the issuing tenant id (every effect runs under this tenant)
 * @param widgetType  the token type claim — must be {@code "equipment-photo"} (4210 on mismatch)
 * @param workOrderId the DRAFT WorkOrder the nameplate read enriches
 * @param expiresAt   the token expiry instant
 */
public record EquipmentPhotoToken(UUID tenantId, String widgetType, UUID workOrderId,
                                  Instant expiresAt) {
}
