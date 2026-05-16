package com.kumouri.kmodigipresbe.service.timetracking;

import com.kumouri.kmodigipresbe.model.timetracking.TimeEntry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Deterministic, total, N-segment local-day midnight-split engine (Phase D — D-D3).
 *
 * <h2>Contract</h2>
 * <ul>
 *   <li><strong>Same local day:</strong> returns exactly one {@link TimeEntry} with
 *       {@code splitGroupId == null} (the overwhelmingly common case — no split).</li>
 *   <li><strong>Mon → Tue (one midnight boundary):</strong> returns exactly two entries
 *       sharing a non-null {@code splitGroupId}. Half-open {@code [start, end)}:
 *       the boundary instant belongs to the earlier day; no zero-length trailing row.</li>
 *   <li><strong>&gt; 24h (two+ boundaries):</strong> returns three or more entries,
 *       all sharing the same {@code splitGroupId}.</li>
 *   <li><strong>Zero-length session</strong> ({@code start == end}): one row with
 *       {@code durationSeconds == 0} and {@code splitGroupId == null}.</li>
 * </ul>
 *
 * <h2>Zone precedence (D-D3b)</h2>
 * <ol>
 *   <li>Caller-supplied {@code zoneId} (the browser zone sent by the FE on stop/save)</li>
 *   <li>{@code kmosf.timetracking.default-zone} application property</li>
 *   <li>{@link ZoneOffset#UTC} (safe deterministic fallback for tests and zone-less
 *       deployments)</li>
 * </ol>
 *
 * <h2>Thread safety</h2>
 * Pure function — stateless, no mutable shared state.
 */
@Service
public class TimeSplitService {

    @Value("${kmosf.timetracking.default-zone:UTC}")
    private String defaultZoneName;

    /**
     * Splits the given {@link TimeEntry} (which must have non-null {@code startedAt} and
     * {@code endedAt}) into N segments along local calendar-day boundaries in the resolved
     * zone. The first segment <em>reuses the same entity id</em> so callers can update-in-place;
     * additional segments get fresh ids. All segments carry the same
     * {@code userId/projectId/taskId/description/source/billable/rateAmount} as the original.
     *
     * @param entry  the template entry; {@code startedAt} and {@code endedAt} must be non-null
     * @param zoneId optional caller-supplied zone override (may be null)
     * @return list of 1..N entries (length 1 with null {@code splitGroupId} for same-day sessions)
     */
    public List<TimeEntry> split(TimeEntry entry, String zoneId) {
        Instant start = entry.getStartedAt();
        Instant end   = entry.getEndedAt();

        ZoneId zone = resolveZone(zoneId);

        // Enumerate local-day midnight instants strictly between start and end
        List<Instant> boundaries = computeBoundaries(start, end, zone);

        if (boundaries.isEmpty()) {
            // Same local day — no split; one row, splitGroupId == null
            long dur = Math.max(0L, end.getEpochSecond() - start.getEpochSecond());
            return List.of(entry.toBuilder()
                    .startedAt(start)
                    .endedAt(end)
                    .durationSeconds(dur)
                    .splitGroupId(null)
                    .build());
        }

        // Multi-day split — assign a shared group id
        UUID groupId = UUID.randomUUID();
        List<Instant> cuts = new ArrayList<>();
        cuts.add(start);
        cuts.addAll(boundaries);
        cuts.add(end);

        List<TimeEntry> segments = new ArrayList<>();
        for (int i = 0; i < cuts.size() - 1; i++) {
            Instant segStart = cuts.get(i);
            Instant segEnd   = cuts.get(i + 1);
            // Half-open [segStart, segEnd): skip any zero-length trailing segment
            if (!segStart.isBefore(segEnd)) {
                continue;
            }
            long dur = segEnd.getEpochSecond() - segStart.getEpochSecond();
            TimeEntry seg = entry.toBuilder()
                    .id(i == 0 ? entry.getId() : UUID.randomUUID()) // first segment reuses the original id
                    .startedAt(segStart)
                    .endedAt(segEnd)
                    .durationSeconds(dur)
                    .splitGroupId(groupId)
                    // Clear auditing fields so the callback re-stamps them on save
                    .createdAt(i == 0 ? entry.getCreatedAt() : null)
                    .updatedAt(null)
                    .version(i == 0 ? entry.getVersion() : null)
                    .build();
            segments.add(seg);
        }

        return segments;
    }

    /**
     * Resolves the effective {@link ZoneId} with the documented precedence:
     * caller-supplied → {@code kmosf.timetracking.default-zone} → {@link ZoneOffset#UTC}.
     */
    ZoneId resolveZone(String callerZone) {
        if (callerZone != null && !callerZone.isBlank()) {
            try {
                return ZoneId.of(callerZone);
            } catch (Exception ignored) {
                // fall through to default
            }
        }
        if (defaultZoneName != null && !defaultZoneName.isBlank()) {
            try {
                return ZoneId.of(defaultZoneName);
            } catch (Exception ignored) {
                // fall through to UTC
            }
        }
        return ZoneOffset.UTC;
    }

    /**
     * Returns the local-midnight instants (as UTC {@link Instant}) that fall strictly
     * between {@code start} (exclusive) and {@code end} (exclusive) in the given zone.
     * Half-open boundary: a midnight that equals {@code end} is excluded (it belongs to
     * the earlier day by the half-open interval convention).
     */
    private List<Instant> computeBoundaries(Instant start, Instant end, ZoneId zone) {
        List<Instant> result = new ArrayList<>();
        // The local date of the start instant
        LocalDate startDay = start.atZone(zone).toLocalDate();
        // Walk forward one day at a time, collecting midnight boundaries
        LocalDate day = startDay.plusDays(1);
        while (true) {
            Instant midnight = day.atStartOfDay(zone).toInstant();
            // Strictly between start (exclusive) and end (exclusive)
            if (!midnight.isAfter(start)) {
                day = day.plusDays(1);
                continue;
            }
            if (!midnight.isBefore(end)) {
                // Midnight is at or after end — stop (half-open: boundary at end belongs to earlier day)
                break;
            }
            result.add(midnight);
            day = day.plusDays(1);
        }
        return result;
    }
}
