package com.kumouri.kmodigipresbe.module.chairfill.model;

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
 * ChairFill CF-3 — a single time-boxed SMS offer of a freed slot to one ranked waitlisted contact. One
 * row per (freed booking, offered contact): {@code GapFillService} mints these (top-N by inverted
 * no-show risk) and texts each contact; an inbound YES is correlated back to the most-recent
 * still-{@code OFFERED}, un-expired offer for that tenant + phone, which then drives the atomic slot
 * claim ({@code WaitlistClaimService}).
 *
 * <h2>Status lifecycle</h2>
 * <ul>
 *   <li>{@code OFFERED} — sent, awaiting a reply (until {@code expiresAt}).</li>
 *   <li>{@code CLAIMED} — this contact's YES won the slot (the {@code WaitlistClaim} {@code findAndModify}
 *       returned them the slot); a real Booking was created for them.</li>
 *   <li>{@code SUPERSEDED} — a sibling offer for the same freed slot was CLAIMED first; this contact's
 *       later YES gets the apologetic auto-reply.</li>
 *   <li>{@code EXPIRED} — {@code expiresAt} passed with no claim.</li>
 * </ul>
 *
 * <p>The {@code @Version} field is the second-line optimistic-lock backstop on the status flip (a lost
 * update throws {@code OptimisticLockingFailureException}); the <em>primary</em> race gate is the
 * slot-level {@link WaitlistClaim} {@code findAndModify} with the {@code claimedByContactId:null} guard
 * (CF-3 D2 — the {@code WorkOrderNumberGenerator} precedent), so two near-simultaneous YESs resolve to
 * exactly one CLAIMED + one apology, never a double-book.
 *
 * <p>System ledger — {@code TenantScoped} for isolation but NOT {@code Auditable} (an automation record,
 * not a CRM entity — the {@code ReminderLog} / {@code CoverageNudgeLog} rationale).
 */
@Document("chairfill_waitlist_offers")
@CompoundIndex(name = "tenant_freed_status_idx",
        def = "{ 'tenantId': 1, 'freedBookingId': 1, 'status': 1 }")
@CompoundIndex(name = "tenant_phone_status_sent_idx",
        def = "{ 'tenantId': 1, 'contactPhone': 1, 'status': 1, 'sentAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistOffer implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The cancelled booking whose freed slot this offer is for (the slot key). */
    private UUID freedBookingId;

    /** The {@link WaitlistEntry} this offer was generated from. */
    private UUID waitlistEntryId;

    /** The offered contact. */
    private UUID contactId;

    /**
     * The contact's phone in the form it was texted at — the correlation key for an inbound YES
     * ({@code From} → the most-recent OFFERED, un-expired offer for this tenant + phone).
     */
    private String contactPhone;

    /** The freed slot's stylist (copied from the cancelled booking) — for the winner's new booking. */
    private UUID staffMemberId;

    /** The freed slot's service (copied from the cancelled booking) — for the winner's new booking. */
    private String serviceMenuItemId;

    /** Snapshot of the service name at offer time. */
    private String serviceMenuItemName;

    /** The freed slot window (copied from the cancelled booking) — the winner's new booking window. */
    private Instant slotStart;
    private Instant slotEnd;

    /** 0-based rank in the ranked offer batch (0 = best, most-likely-to-show). */
    private int rank;

    @Builder.Default
    private Status status = Status.OFFERED;

    private Instant sentAt;

    /** After this, the offer can no longer be claimed (a late YES gets the apology). */
    private Instant expiresAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { OFFERED, CLAIMED, SUPERSEDED, EXPIRED }
}
