package com.kumouri.kmodigipresbe.module.quoting.closer;

import java.util.UUID;

/**
 * T11 (Home "QuoteCloser") — the {@link QuoteCloserConfig} upsert request body + the read projection.
 * Drops the server-managed fields (id / tenantId / version / timestamps); the
 * {@code MidnightResponderConfigDTO} (T3) precedent.
 *
 * <p>All fields are nullable so a partial upsert preserves prior values (the {@link #applyTo} path). The
 * {@link #campaignId} is validated against the tenant's {@code NurtureCampaign}s by the controller (4471).
 *
 * @param campaignId            the {@code NurtureCampaign} a {@code NEW} (un-accepted) quote's contact
 *                              auto-enrolls into (null ⇒ the enrollment job routes nowhere for this tenant)
 * @param unacceptedWindowHours how long a {@code NEW} quote may sit unaccepted before the cadence enrolls
 *                              it (hours; default {@link QuoteCloserConfig#DEFAULT_UNACCEPTED_WINDOW_HOURS}
 *                              on create)
 */
public record QuoteCloserConfigDTO(
        UUID campaignId,
        Integer unacceptedWindowHours) {

    /** A read projection of a persisted config. */
    static QuoteCloserConfigDTO from(QuoteCloserConfig c) {
        return new QuoteCloserConfigDTO(c.getCampaignId(), c.getUnacceptedWindowHours());
    }

    QuoteCloserConfig toNewEntity(UUID tenantId) {
        return QuoteCloserConfig.builder()
                .tenantId(tenantId)
                .campaignId(campaignId)
                .unacceptedWindowHours(unacceptedWindowHours != null && unacceptedWindowHours > 0
                        ? unacceptedWindowHours
                        : QuoteCloserConfig.DEFAULT_UNACCEPTED_WINDOW_HOURS)
                .build();
    }

    QuoteCloserConfig applyTo(QuoteCloserConfig existing) {
        return existing.toBuilder()
                .campaignId(campaignId != null ? campaignId : existing.getCampaignId())
                .unacceptedWindowHours(unacceptedWindowHours != null && unacceptedWindowHours > 0
                        ? unacceptedWindowHours
                        : existing.getUnacceptedWindowHours())
                .build();
    }
}
