package com.kumouri.kmodigipresbe.module.chairfill.model;

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
 * ChairFill CF-3 — a client's standing request to be offered a gap-fill slot. Created either by the
 * public {@code salon-waitlist} join widget ({@code WaitlistWidgetController}) or by staff, this is the
 * pool {@code WaitlistMatchService} ranks (inverted no-show risk) when a booking cancels and frees a
 * slot.
 *
 * <h2>Consent capture (plan §4 TCPA)</h2>
 * The waitlist join <strong>is</strong> the SMS opt-in: a contact is only ever offered a slot when
 * {@code smsOptIn} is true. The {@code smsOptIn=true} default on the join widget reflects that joining a
 * waitlist to be texted about openings is the consent act; a STOP inbound flips the contact's
 * {@code sms-opt-out} tag (the CF-2 {@code RiskTieredPreventionService#SMS_OPT_OUT_TAG}), which the
 * gap-fill honors before any offer (so a STOP closes the loop even on an opted-in entry).
 *
 * <h2>Matching scope</h2>
 * {@code serviceMenuItemId} / {@code preferredStaffMemberId} are optional filters: when set, the entry
 * only matches a freed slot for that service / stylist; when null, it matches any freed slot (a flexible
 * waitlister). {@code earliestStart} / {@code latestStart} bound the time window the client will accept
 * (both optional — null = no bound on that side).
 *
 * <p>{@code status} moves {@code OPEN → (offers fire) → FULFILLED} (the client claimed a slot) or stays
 * OPEN for the next opening; staff can {@code CANCELLED} it. Only OPEN entries are ever offered.
 */
@Document("chairfill_waitlist_entries")
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

    /** Optional — only match a freed slot for this {@link com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem}. */
    private String serviceMenuItemId;

    /** Optional — only match a freed slot with this stylist. Null = any stylist. */
    private UUID preferredStaffMemberId;

    /** Optional lower bound on the acceptable slot start; null = no lower bound. */
    private Instant earliestStart;

    /** Optional upper bound on the acceptable slot start; null = no upper bound. */
    private Instant latestStart;

    /** SMS consent — only an opted-in entry is ever offered a slot (plan §4 TCPA default-safe). */
    @Builder.Default
    private boolean smsOptIn = true;

    @Builder.Default
    private Status status = Status.OPEN;

    /** Free-form note from the client (e.g. "any afternoon works"). */
    private String notes;

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
