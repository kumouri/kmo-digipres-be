package com.kumouri.kmodigipresbe.model.sync;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One client-side field mutation to apply during a push. The {@code fields} map
 * contains only the fields the client wants to write — partial updates are allowed.
 * {@code clientUpdatedAt} is the timestamp of the last successful pull for this
 * document; it is used to detect server-side conflicts (server wrote after this
 * timestamp). The client always wins (last-write-wins per field); conflicts are
 * surfaced in {@link SyncPushResult#conflictedFields()} so the caller can log or
 * present them.
 */
public record SyncMutation(UUID id, Map<String, Object> fields, Instant clientUpdatedAt) {
}
