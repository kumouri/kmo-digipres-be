package com.kumouri.kmodigipresbe.model.sync;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * One item returned in a pull response. {@code type} is {@code "UPDATE"} for
 * a created or modified document, or {@code "DELETE"} for a tombstoned one.
 * {@code data} carries the full document fields for UPDATEs and is {@code null}
 * for DELETEs. {@code changedAt} reflects the server {@code updatedAt} (for
 * updates) or {@code deletedAt} (for tombstones).
 */
public record SyncChange(String type, UUID id, Map<String, Object> data, Instant changedAt) {
}
