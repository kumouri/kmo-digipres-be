package com.kumouri.kmodigipresbe.service.sync;

/**
 * Field-level conflict resolution strategy for the mobile delta-sync API.
 * v1 ships {@code LAST_WRITE_WINS} only: the client's value is always applied
 * and conflicts are surfaced in {@link com.kumouri.kmodigipresbe.model.sync.SyncPushResult#conflictedFields()}
 * rather than rejected. Future versions may add {@code MANUAL_MERGE} for
 * user-facing resolution prompts.
 */
public enum ConflictPolicy {
    LAST_WRITE_WINS
}
