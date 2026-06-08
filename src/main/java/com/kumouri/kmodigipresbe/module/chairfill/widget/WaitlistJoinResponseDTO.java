package com.kumouri.kmodigipresbe.module.chairfill.widget;

import com.kumouri.kmodigipresbe.module.chairfill.model.WaitlistEntry;

import java.util.UUID;

/**
 * Response from the public waitlist-join widget endpoint — the created (or re-used) contact id, the new
 * {@link WaitlistEntry} id, and its status. The client is now in the pool and will be texted a time-boxed
 * offer if a matching slot frees up.
 */
public record WaitlistJoinResponseDTO(
        UUID contactId,
        UUID waitlistEntryId,
        WaitlistEntry.Status status) {
}
