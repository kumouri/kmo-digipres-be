package com.kumouri.kmodigipresbe.model.sync;

import java.util.List;
import java.util.UUID;

/**
 * Per-mutation result returned by the sync push endpoint. {@code applied} is
 * {@code false} when the document did not exist server-side (ID not found for
 * this tenant). {@code conflictedFields} lists fields where the server had a
 * newer value than {@code clientUpdatedAt} — these are applied anyway
 * (last-write-wins) but surfaced so the client can prompt the user to review.
 */
public record SyncPushResult(UUID id, boolean applied, List<String> conflictedFields) {
}
