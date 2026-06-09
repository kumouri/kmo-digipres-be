package com.kumouri.kmodigipresbe.module.realestate.responder;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * T3 (Real Estate "Midnight Responder") — the per-tenant configuration for the routing + completeness
 * layer over the shipped RE concierge. One row per tenant (unique {@code tenant_idx {tenantId}}); a
 * tenant-scoped CRM entity ({@code Auditable}).
 *
 * <h2>What it configures (never hardcoded)</h2>
 * <ul>
 *   <li><strong>Tier → campaign mapping</strong> ({@link #warmCampaignId} / {@link #coldCampaignId}) —
 *       the {@code NurtureCampaign} ids a WARM / COLD concierge lead is auto-enrolled into when the
 *       UNCHANGED nightly scorer tiers it (the T3 tier-routing headline). A null id ⇒ that tier routes
 *       nowhere (a clean no-op — no enroll). HOT is NOT mapped here: the RE-2 {@code LeadHandoffService}
 *       hot-handoff owns HOT, untouched. Validated at save-time against the tenant's campaigns (4381).</li>
 *   <li><strong>{@link #delegateHandoffToResponder}</strong> — when true, a strict concierge
 *       {@code HANDOFF} turn (the model declined to ground an answer) additionally delegates to the E2
 *       responder default-handoff (staff notify + generic reply). Default false ⇒ the RE-1 handoff reply
 *       is byte-identical. The off-listing ({@code NO_LISTING}) path always delegates when the responder
 *       handoff is wired, independent of this flag.</li>
 *   <li><strong>{@link #afterHoursStartHour} / {@link #afterHoursEndHour}</strong> — the local-hour
 *       business-hours window [start, end) used by the latency-stats endpoint to compute the
 *       "answered after-hours" share (the 24/7 demo stat). Defaults 8..18.</li>
 * </ul>
 *
 * <h2>Blast radius zero</h2>
 * A tenant with no {@code MidnightResponderConfig} row behaves byte-identically to before T3: the
 * tier-router finds no mapping ⇒ no enroll; the concierge handoff delegate uses its default
 * (NO_LISTING-only) behavior; the latency window defaults apply. The doc only ever ADDS routing on top of
 * the shipped concierge — never changes a grounded answer / qualification / booking outcome.
 */
@Document("re_midnight_responder_config")
@CompoundIndex(name = "tenant_idx", def = "{ 'tenantId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MidnightResponderConfig implements TenantScoped {

    /** The default business-hours window [start, end) (local hour) for the after-hours latency share. */
    public static final int DEFAULT_AFTER_HOURS_START = 8;
    public static final int DEFAULT_AFTER_HOURS_END = 18;

    @Id
    private UUID id;

    private UUID tenantId;

    /** The {@code NurtureCampaign} a WARM concierge lead auto-enrolls into; null ⇒ WARM routes nowhere. */
    private UUID warmCampaignId;

    /** The long-cadence {@code NurtureCampaign} a COLD concierge lead auto-enrolls into; null ⇒ no-op. */
    private UUID coldCampaignId;

    /** When true, a strict concierge {@code HANDOFF} turn also delegates to the E2 responder handoff. */
    @Builder.Default
    private boolean delegateHandoffToResponder = false;

    /** Business-hours window start (local hour, inclusive) for the after-hours latency share. */
    @Builder.Default
    private int afterHoursStartHour = DEFAULT_AFTER_HOURS_START;

    /** Business-hours window end (local hour, exclusive) for the after-hours latency share. */
    @Builder.Default
    private int afterHoursEndHour = DEFAULT_AFTER_HOURS_END;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
