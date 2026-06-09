package com.kumouri.kmodigipresbe.model.nurture;

import com.kumouri.kmodigipresbe.audit.Auditable;
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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A reactivation / nurture campaign definition (E1 — Nurture / Cadence Engine). Tenant-scoped CRM
 * entity ({@code TenantScoped} via {@code Auditable} + the tenant-stamping machinery; {@code Auditable}
 * because a campaign IS a first-class staff-managed record, unlike the {@link NurtureSendLog} ledger).
 *
 * <p>The campaign is the <strong>only</strong> place vertical-specific knobs live — both the dormancy
 * {@link #segments} (which day-windows / value-bands map a contact into a {@link DormancyBucket}) and
 * the {@link #steps} (the ordered SMS+EMAIL cadence). So the same engine serves Real Estate, Health,
 * and Home verticals purely by seeding different campaign documents.
 *
 * <p>Unique {@code tenant_name_idx {tenantId,name}} — a campaign name is unique per tenant (the admin
 * UX + find anchor).
 */
@Document("nurture_campaigns")
@CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NurtureCampaign implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;

    /** When false the runner exits any in-flight enrollment ("campaign inactive") and segmentation 4302s. */
    @Builder.Default
    private boolean active = true;

    /** Ordered dormancy-segment rules — a contact matches the first one whose window contains it. */
    @Builder.Default
    private List<NurtureSegmentDefinition> segments = new ArrayList<>();

    /** Ordered multi-touch cadence steps (0-based, contiguous stepIndex). */
    @Builder.Default
    private List<NurtureCadenceStep> steps = new ArrayList<>();

    /**
     * TCPA frequency cap — the max nurture touches one contact may receive in the rolling window
     * ({@code kmosf.modules.nurture.frequency-window-days}, across ALL campaigns). The runner defers
     * (does not drop) a step that would exceed this. Default seeded from
     * {@code kmosf.modules.nurture.default-max-touches-per-window} by the controller when unset.
     */
    @Builder.Default
    private int maxTouchesPerContactPerWindow = 2;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
