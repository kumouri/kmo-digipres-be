package com.kumouri.kmodigipresbe.module.stylermatch.model;

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
import java.util.List;
import java.util.UUID;

/**
 * T12 (Salon "StylerMatch") — a client's submitted stylist-match request: the office-inbox row, the
 * analytics source, and the accept idempotency breadcrumb. Created by {@code StylerMatchService} on a
 * public-widget or staff submission — it echoes the {@link MatchRequest} the match was scored from, the
 * ranked {@link RankedMatch} board, and a lifecycle {@link StylerMatchStatus}. Accepting it books a real
 * salon {@code Booking} (P3) and stamps {@link #bookingId} + {@link #selectedStaffMemberId}.
 *
 * <p>The stylist-side twin of the T9 {@code StyleConsult}: same office-inbox / nullable-field shape.
 * {@code tenant_status_created_idx {tenantId, status, createdAt desc}} backs the office inbox list as a
 * pure DB read (newest first within a status filter); {@code tenant_contact_idx} backs the per-contact
 * lookup. {@code TenantScoped} + {@code Auditable} (a coordinator-facing CRM record). Not a
 * {@code CustomFieldHost}.
 */
@Document("styler_matches")
@CompoundIndex(name = "tenant_status_created_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'createdAt': -1 }")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StylerMatch implements TenantScoped, Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The client this match is for (nullable on a fully-anonymous public request). */
    private UUID contactId;

    /** The client's contact phone (E.164 where known) — the booking-link SMS target. Nullable. */
    private String contactPhone;

    /** The client's contact email (where supplied). Nullable. */
    private String contactEmail;

    /** The requested service id (the hard-eligibility signal + what accept books). Nullable. */
    private String serviceMenuItemId;

    /** Snapshot of the requested service name (for the office + the booking record). Nullable. */
    private String serviceMenuItemName;

    /** The style category the client requested (the primary specialty-fit signal). Nullable. */
    private String styleCategory;

    /** Requested/own hair length. Nullable. */
    private String length;

    /** Requested/own hair texture. Nullable. */
    private String texture;

    /** Requested/own color. Nullable. */
    private String color;

    /** An explicit preferred stylist the client named (the largest preference bump). Nullable. */
    private UUID preferredStaffMemberId;

    /** The start of the slot the client wants (the availability signal). Nullable. */
    private Instant slotStart;

    /** The end of the requested slot. Nullable. */
    private Instant slotEnd;

    /** A free-text note the client typed. Nullable. */
    private String notes;

    /** The ranked stylist board (best-fit first), each with an explained rationale. */
    @Builder.Default
    private List<RankedMatch> rankedMatches = List.of();

    /** The composite confidence of the match (how many signals were available), in [0,1]. */
    private double confidence;

    @Builder.Default
    private StylerMatchStatus status = StylerMatchStatus.NEW;

    /** The stylist booked on accept (P3). Nullable until booked. */
    private UUID selectedStaffMemberId;

    /**
     * The 1-based rank of {@link #selectedStaffMemberId} within {@link #rankedMatches} at accept time —
     * the analytics "which rank got booked" signal. Null until booked.
     */
    private Integer selectedRank;

    /** The salon {@code Booking} created on accept (P3); set once, the idempotency breadcrumb. Nullable. */
    private UUID bookingId;

    /** Set when the client accepts + the booking is created (idempotency breadcrumb). Nullable. */
    private Instant bookedAt;

    /** The booking link that was texted on accept (per-tenant config snapshot). Nullable. */
    private String bookingLinkSent;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "STYLER_MATCH";
    }
}
