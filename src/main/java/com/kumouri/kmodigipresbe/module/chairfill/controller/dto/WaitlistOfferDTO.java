package com.kumouri.kmodigipresbe.module.chairfill.controller.dto;

import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistOffer;

import java.time.Instant;
import java.util.UUID;

/**
 * One recent gap-fill offer on the ChairFill CF-5 waitlist board (CF-5a — the staff-facing read
 * contract the board FE consumes). A lean projection of a {@link WaitlistOffer}: who was offered what
 * freed slot, at what rank, and where the offer stands ({@code OFFERED|CLAIMED|SUPERSEDED|EXPIRED}).
 *
 * <p>A flat projection (the {@code MissedCallInboxItemDTO} precedent — no cross-collection join). The
 * offer already denormalizes {@code contactPhone} + {@code serviceMenuItemName} at offer time, so the
 * board can render those directly; {@code contactId} / {@code staffMemberId} are resolved board-side
 * against the contacts/staff it already loads.
 *
 * @param id                  the WaitlistOffer id
 * @param freedBookingId      the cancelled booking whose freed slot this offer is for (the slot key)
 * @param waitlistEntryId     the WaitlistEntry this offer was generated from
 * @param contactId           the offered client (resolve to a name board-side)
 * @param contactPhone        the phone the offer was texted at (denormalized)
 * @param staffMemberId       the freed slot's stylist
 * @param serviceMenuItemId   the freed slot's service id
 * @param serviceMenuItemName the service name snapshotted at offer time (denormalized)
 * @param slotStart           the freed slot window start
 * @param slotEnd             the freed slot window end
 * @param rank                0-based rank in the ranked offer batch (0 = best, most-likely-to-show)
 * @param status              OFFERED | CLAIMED | SUPERSEDED | EXPIRED
 * @param sentAt              when the offer SMS was sent (newest-first ordering key)
 * @param expiresAt           after this the offer can no longer be claimed (a late YES gets the apology)
 * @param createdAt           when the offer row was minted
 */
public record WaitlistOfferDTO(
        UUID id,
        UUID freedBookingId,
        UUID waitlistEntryId,
        UUID contactId,
        String contactPhone,
        UUID staffMemberId,
        String serviceMenuItemId,
        String serviceMenuItemName,
        Instant slotStart,
        Instant slotEnd,
        int rank,
        WaitlistOffer.Status status,
        Instant sentAt,
        Instant expiresAt,
        Instant createdAt) {

    public static WaitlistOfferDTO from(WaitlistOffer o) {
        return new WaitlistOfferDTO(
                o.getId(),
                o.getFreedBookingId(),
                o.getWaitlistEntryId(),
                o.getContactId(),
                o.getContactPhone(),
                o.getStaffMemberId(),
                o.getServiceMenuItemId(),
                o.getServiceMenuItemName(),
                o.getSlotStart(),
                o.getSlotEnd(),
                o.getRank(),
                o.getStatus(),
                o.getSentAt(),
                o.getExpiresAt(),
                o.getCreatedAt());
    }
}
