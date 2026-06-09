package com.kumouri.kmodigipresbe.model.waitlist;

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
import java.util.UUID;

/**
 * E4 — a client's standing request to be offered a gap-fill slot, expressed
 * <strong>vertical-agnostically</strong>. The generic sibling of ChairFill CF-3's
 * {@code module.chairfill.model.WaitlistEntry} (which stays byte-equivalent): this carries a
 * {@code slotType}/{@code providerId} match pair (a {@code String}/{@code UUID}, no salon
 * {@code serviceMenuItemId} / {@code staffMemberId} coupling) so any vertical can use it.
 *
 * <p>This is the pool {@link com.kumouri.kmodigipresbe.service.waitlist.WaitlistRankingService} ranks
 * (inverted show-risk — most-likely-to-show first) when a consumer frees a slot and calls
 * {@link com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine#gapFill}.
 *
 * <h2>Consent (TCPA, the CF-3 posture)</h2>
 * The waitlist join <strong>is</strong> the SMS opt-in: a contact is only ever offered a slot when
 * {@code smsOptIn} is true, and a contact carrying the {@code sms-opt-out} tag is dropped before any offer
 * by the {@code GapFillEngine} send gate.
 *
 * <h2>Matching scope</h2>
 * {@code slotType} / {@code providerId} are optional filters: when set, the entry only matches a freed slot
 * for that type / provider; when null, it matches any freed slot (a flexible waitlister).
 * {@code earliestStart} / {@code latestStart} bound the acceptable window (both optional).
 *
 * <h2>Show-likelihood stats — entry-carried (the key vertical-agnostic divergence from CF-3)</h2>
 * CF-3's matcher derives show-likelihood from the salon's <em>Booking history</em> (a salon-coupled load).
 * The generic engine has no Booking, so the ranking signal travels <strong>on the entry</strong>:
 * {@code priorNoShowCount} / {@code priorVisitCount} / {@code lastVisitAt}. A consumer stamps whatever it
 * knows (zeros / null = a cold-start client, treated low-risk, never punished — the CF-1 D1 posture). This
 * keeps {@link com.kumouri.kmodigipresbe.service.waitlist.WaitlistRankingService} pure + deterministic +
 * vertical-agnostic.
 *
 * <p>{@code status} moves {@code OPEN → FULFILLED} (the client claimed a slot) or stays OPEN for the next
 * opening; staff can {@code CANCELLED} it. Only OPEN entries are ever offered.
 */
@Document("waitlist_entries")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1 }")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WaitlistEntry implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The waitlisted client. */
    private UUID contactId;

    /** Optional — only match a freed slot of this {@code slotType}. Null = any type. */
    private String slotType;

    /** Optional — only match a freed slot with this provider (stylist / clinician / tech). Null = any. */
    private UUID providerId;

    /** Optional lower bound on the acceptable slot start; null = no lower bound. */
    private Instant earliestStart;

    /** Optional upper bound on the acceptable slot start; null = no upper bound. */
    private Instant latestStart;

    /** SMS consent — only an opted-in entry is ever offered a slot (TCPA default-safe, the CF-3 posture). */
    @Builder.Default
    private boolean smsOptIn = true;

    @Builder.Default
    private Status status = Status.OPEN;

    /** Free-form note from the client (e.g. "any afternoon works"). */
    private String notes;

    // ── Show-likelihood stats (entry-carried; the vertical-agnostic ranking signal) ──

    /** Prior no-shows for this client, as known to the consumer. 0 = none/unknown. */
    @Builder.Default
    private int priorNoShowCount = 0;

    /** Prior completed visits for this client, as known to the consumer. 0 = none/unknown (cold start). */
    @Builder.Default
    private int priorVisitCount = 0;

    /** When this client last visited (Instant); null = never/unknown (cold start, treated low-risk). */
    private Instant lastVisitAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "WAITLIST_ENTRY";
    }

    public enum Status { OPEN, FULFILLED, CANCELLED }
}
