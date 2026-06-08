package com.kumouri.kmodigipresbe.module.realestate.model;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — one real-estate listing an agent has loaded into the console.
 *
 * <p>The PoC runs entirely on agent-uploaded data (RE-1 §4 decision 6) — there is <strong>no live
 * MLS/IDX feed</strong>; {@link #source} defaults to {@code "AGENT_UPLOAD"} and {@link #externalId} is
 * reserved so an IDX importer is a clean future adapter rather than a model change.
 *
 * <p>The {@link #trackedPhone} (E.164) is the Twilio number buyers text — the primary correlation key for
 * an inbound SMS (RE-1 §6.6): the {@code ConciergeInboundRouter} resolves the listing by
 * {@code trackedPhone == To}. It is unique per tenant (compound index {@code {tenantId, trackedPhone}}).
 *
 * <p>{@code TenantScoped} for isolation. Not {@code Auditable} (RE-1's listing CRUD is a lightweight
 * agent-facing surface, not a finance/contract entity — the {@code WaitlistOffer} rationale); a full
 * audit trail can be layered later without a model change.
 */
@Document("re_listings")
@CompoundIndex(name = "tenant_tracked_phone_idx",
        def = "{ 'tenantId': 1, 'trackedPhone': 1 }", unique = true, sparse = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Listing implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String addressLine;
    private String city;
    private String state;
    private String zip;

    /** Optional MLS number (nullable — agent-entered; reserved for future IDX matching). */
    private String mlsNumber;

    private BigDecimal price;
    private Integer beds;
    private BigDecimal baths;
    private Integer sqft;

    @Builder.Default
    private ListingStatus status = ListingStatus.ACTIVE;

    /** The listing agent's contact (for handoff routing / display). Nullable. */
    private UUID agentContactId;

    /** The listing agent's user id (the owner-user the hot-handoff notifies). Nullable. */
    private UUID ownerUserId;

    /**
     * The tracked Twilio SMS number buyers text (E.164). The primary inbound-SMS correlation key
     * ({@code To} → this listing). Unique per tenant; nullable (a listing can exist before a number is
     * assigned). The compound index is {@code sparse} so multiple un-numbered listings don't collide on
     * a single null key.
     */
    private String trackedPhone;

    /** Provenance of the listing data. Default {@code "AGENT_UPLOAD"}; reserved {@code "IDX"} (decision 6). */
    @Builder.Default
    private String source = "AGENT_UPLOAD";

    /** Reserved external id for a future IDX importer (RESO listing key, etc.). Nullable. */
    private String externalId;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum ListingStatus { ACTIVE, PENDING, SOLD }
}
