package com.kumouri.kmodigipresbe.model.waitlist;

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
 * E4 — a single time-boxed SMS offer of a freed slot to one ranked waitlisted contact, expressed
 * <strong>vertical-agnostically</strong>. The generic sibling of ChairFill CF-3's
 * {@code module.chairfill.model.WaitlistOffer} (which stays byte-equivalent): one row per (freed slot,
 * offered contact). {@link com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine} mints these (top-N by
 * inverted show-risk) and texts each contact; an inbound YES is correlated back to the most-recent
 * still-{@code OFFERED}, un-expired offer for that tenant + phone, which then drives the atomic slot claim
 * ({@link com.kumouri.kmodigipresbe.service.waitlist.WaitlistClaimEngine}).
 *
 * <h2>Status lifecycle</h2>
 * <ul>
 *   <li>{@code OFFERED} — sent, awaiting a reply (until {@code expiresAt}).</li>
 *   <li>{@code CLAIMED} — this contact's YES won the slot (the slot-level {@code findAndModify} returned
 *       them the slot); the consumer's {@link SlotMaterializer} created the real domain record.</li>
 *   <li>{@code SUPERSEDED} — a sibling offer for the same freed slot was CLAIMED first; this contact's
 *       later YES gets the apologetic auto-reply.</li>
 *   <li>{@code EXPIRED} — {@code expiresAt} passed with no claim.</li>
 * </ul>
 *
 * <p>The {@code @Version} field is the second-line optimistic-lock backstop on the status flip; the
 * <em>primary</em> race gate is the slot-level claim {@code findAndModify} with the
 * {@code claimedByContactId:null} guard (the {@code WorkOrderNumberGenerator} / CF-3 precedent), so two
 * near-simultaneous YESs resolve to exactly one CLAIMED + one apology, never a double-materialize.
 *
 * <p>The {@code slotType}/{@code providerId}/{@code slotStart}/{@code slotEnd}/{@code durationMinutes}
 * columns snapshot the {@link WaitlistSlot} at offer time so the claim path can reconstruct the slot and
 * hand it to the {@link SlotMaterializer} without a re-load.
 *
 * <p>System ledger — {@code TenantScoped} for isolation but NOT {@code Auditable} (an automation record,
 * not a CRM entity — the chairfill {@code WaitlistOffer} / {@code NurtureSendLog} rationale).
 */
@Document("waitlist_offers")
@CompoundIndex(name = "tenant_slot_status_idx",
        def = "{ 'tenantId': 1, 'slotKey': 1, 'status': 1 }")
@CompoundIndex(name = "tenant_phone_status_sent_idx",
        def = "{ 'tenantId': 1, 'contactPhone': 1, 'status': 1, 'sentAt': -1 }")
@CompoundIndex(name = "tenant_status_expires_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'expiresAt': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistOffer implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The opaque id of the freed slot's contended resource (the slot key — the claim doc {@code _id} base). */
    private String slotKey;

    /** The consumer's slot category — the {@link SlotMaterializer} dispatch key on claim. */
    private String slotType;

    /** The {@link WaitlistEntry} this offer was generated from. */
    private UUID waitlistEntryId;

    /** The offered contact. */
    private UUID contactId;

    /**
     * The contact's phone in the form it was texted at — the correlation key for an inbound YES
     * ({@code From} → the most-recent OFFERED, un-expired offer for this tenant + phone).
     */
    private String contactPhone;

    /** The freed slot's provider (copied from the {@link WaitlistSlot}) — for the materializer. Nullable. */
    private UUID providerId;

    /** The freed slot window (copied from the {@link WaitlistSlot}) — for the materializer. */
    private Instant slotStart;
    private Instant slotEnd;

    /** The slot length in minutes (copied from the {@link WaitlistSlot}) — for the materializer. */
    private int durationMinutes;

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
