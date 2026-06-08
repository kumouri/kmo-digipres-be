package com.kumouri.kmodigipresbe.module.chairfill.controller.dto;

import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;

import java.time.Instant;
import java.util.UUID;

/**
 * One OPEN waitlist row on the ChairFill CF-5 waitlist board (CF-5a — the staff-facing read contract
 * the board FE consumes). A lean projection of an OPEN {@link WaitlistEntry}: the fields a staffer
 * needs to see who is waiting and for what, without leaking the full document shape.
 *
 * <p>Like {@code MissedCallInboxItemDTO}, this is a flat projection — no cross-collection join. The
 * {@code contactId} / {@code preferredStaffMemberId} are surfaced as ids the board resolves against
 * the contacts/staff it already loads; the entry itself carries no denormalized name snapshot
 * (unlike {@link WaitlistOfferDTO}, where the service name is snapshotted at offer time).
 *
 * @param id                    the WaitlistEntry id
 * @param contactId             the waitlisted client (resolve to a name board-side)
 * @param serviceMenuItemId     the requested service filter, or null = any service (flexible)
 * @param preferredStaffMemberId the requested stylist filter, or null = any stylist
 * @param earliestStart         the lower bound the client will accept, or null = no lower bound
 * @param latestStart           the upper bound the client will accept, or null = no upper bound
 * @param smsOptIn              whether the entry is SMS-opted-in (only opted-in entries are offered)
 * @param notes                 the client's free-form note (e.g. "any afternoon works"), nullable
 * @param createdAt             when the client joined the waitlist (newest-first ordering key)
 */
public record WaitlistBoardEntryDTO(
        UUID id,
        UUID contactId,
        String serviceMenuItemId,
        UUID preferredStaffMemberId,
        Instant earliestStart,
        Instant latestStart,
        boolean smsOptIn,
        String notes,
        Instant createdAt) {

    public static WaitlistBoardEntryDTO from(WaitlistEntry e) {
        return new WaitlistBoardEntryDTO(
                e.getId(),
                e.getContactId(),
                e.getServiceMenuItemId(),
                e.getPreferredStaffMemberId(),
                e.getEarliestStart(),
                e.getLatestStart(),
                e.isSmsOptIn(),
                e.getNotes(),
                e.getCreatedAt());
    }
}
