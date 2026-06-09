package com.kumouri.kmodigipresbe.module.realestate.responder;

import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — the {@link MidnightResponderConfig} upsert request body. Drops
 * the server-managed fields (id / tenantId / version / timestamps); the {@code ResponderConfigController}
 * {@code ConfigRequest} precedent.
 *
 * <p>All fields are nullable so a partial upsert preserves prior values (the {@link #applyTo} path).
 * Tier campaign ids are validated against the tenant's campaigns by the controller (4381).
 *
 * @param warmCampaignId             the {@code NurtureCampaign} a WARM concierge lead auto-enrolls into
 *                                   (null ⇒ WARM routes nowhere)
 * @param coldCampaignId             the long-cadence {@code NurtureCampaign} a COLD lead auto-enrolls into
 *                                   (null ⇒ COLD routes nowhere)
 * @param delegateHandoffToResponder when true, a strict concierge HANDOFF also delegates to the E2
 *                                   responder handoff (default false on create)
 * @param afterHoursStartHour        business-hours window start (local hour, inclusive) for the
 *                                   after-hours latency share (default 8 on create)
 * @param afterHoursEndHour          business-hours window end (local hour, exclusive) (default 18 on create)
 */
public record MidnightResponderConfigDTO(
        UUID warmCampaignId,
        UUID coldCampaignId,
        Boolean delegateHandoffToResponder,
        Integer afterHoursStartHour,
        Integer afterHoursEndHour) {

    MidnightResponderConfig toNewEntity(UUID tenantId) {
        return MidnightResponderConfig.builder()
                .tenantId(tenantId)
                .warmCampaignId(warmCampaignId)
                .coldCampaignId(coldCampaignId)
                .delegateHandoffToResponder(delegateHandoffToResponder != null && delegateHandoffToResponder)
                .afterHoursStartHour(afterHoursStartHour != null
                        ? afterHoursStartHour : MidnightResponderConfig.DEFAULT_AFTER_HOURS_START)
                .afterHoursEndHour(afterHoursEndHour != null
                        ? afterHoursEndHour : MidnightResponderConfig.DEFAULT_AFTER_HOURS_END)
                .build();
    }

    MidnightResponderConfig applyTo(MidnightResponderConfig existing) {
        return existing.toBuilder()
                .warmCampaignId(warmCampaignId != null ? warmCampaignId : existing.getWarmCampaignId())
                .coldCampaignId(coldCampaignId != null ? coldCampaignId : existing.getColdCampaignId())
                .delegateHandoffToResponder(delegateHandoffToResponder != null
                        ? delegateHandoffToResponder : existing.isDelegateHandoffToResponder())
                .afterHoursStartHour(afterHoursStartHour != null
                        ? afterHoursStartHour : existing.getAfterHoursStartHour())
                .afterHoursEndHour(afterHoursEndHour != null
                        ? afterHoursEndHour : existing.getAfterHoursEndHour())
                .build();
    }
}
