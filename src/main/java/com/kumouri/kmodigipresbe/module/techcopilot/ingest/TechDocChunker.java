package com.kumouri.kmodigipresbe.module.techcopilot.ingest;

import java.util.ArrayList;
import java.util.List;

/**
 * Tech Copilot (T13) — splits a manual / SOP / spec-sheet's text into overlapping windows for embedding
 * (T13-D1, the headline net-new over the RE-1 concierge: a disclosure was one short text → one embed; a
 * manual is multi-page → N chunks).
 *
 * <p><strong>Pure / static / total.</strong> No I/O, no state — safe to call inside a reactive chain (it
 * is cheap CPU work) and trivially unit-testable without Docker ({@code TechDocChunkerTest}).
 *
 * <p>Algorithm: a sliding window of {@code maxChars} characters that advances by {@code maxChars - overlap}
 * each step, so adjacent chunks share {@code overlap} characters — a procedure ("Fault E3 reset: …") that
 * straddles a window boundary is therefore present <em>in full</em> in at least one chunk. The window edge
 * is nudged left to the nearest whitespace within a small look-back so a chunk does not split a word
 * mid-token (purely cosmetic for retrieval quality; never grows the window past {@code maxChars}). Blank /
 * whitespace-only input → an empty list (the caller leaves {@code indexedAt} null). A text shorter than
 * {@code maxChars} → exactly one chunk.
 */
public final class TechDocChunker {

    /** Default window size in characters (T13-D1; {@code kmosf.techcopilot.chunk-size}). */
    public static final int DEFAULT_MAX_CHARS = 1_200;
    /** Default overlap in characters between adjacent windows ({@code kmosf.techcopilot.chunk-overlap}). */
    public static final int DEFAULT_OVERLAP = 200;

    /** How far left of a hard window edge to look for a whitespace break (cosmetic word-boundary nudge). */
    private static final int BOUNDARY_LOOKBACK = 80;

    private TechDocChunker() {
    }

    /** Chunk with the default window/overlap. */
    public static List<String> chunk(String text) {
        return chunk(text, DEFAULT_MAX_CHARS, DEFAULT_OVERLAP);
    }

    /**
     * Splits {@code text} into overlapping windows.
     *
     * @param text     the document text (null / blank → empty list)
     * @param maxChars the maximum window size (clamped to ≥ 1)
     * @param overlap  the overlap between adjacent windows (clamped to {@code [0, maxChars - 1]} so the
     *                 cursor always advances and the chunk count is finite)
     * @return the ordered list of non-blank chunks (each ≤ {@code maxChars} chars)
     */
    public static List<String> chunk(String text, int maxChars, int overlap) {
        List<String> chunks = new ArrayList<>();
        if (text == null) {
            return chunks;
        }
        String normalized = text.strip();
        if (normalized.isEmpty()) {
            return chunks;
        }

        int max = Math.max(1, maxChars);
        // Guarantee forward progress: the step (max - overlap) must be ≥ 1.
        int boundedOverlap = Math.max(0, Math.min(overlap, max - 1));
        int step = max - boundedOverlap;

        int len = normalized.length();
        int start = 0;
        while (start < len) {
            int hardEnd = Math.min(start + max, len);
            int end = hardEnd;
            // Nudge the window edge left to a whitespace boundary (unless this is the final window, or the
            // window is the whole remaining text) so we don't cut a word in half. Never extends the window.
            if (end < len) {
                int boundary = lastWhitespaceWithin(normalized, end, BOUNDARY_LOOKBACK);
                if (boundary > start) {
                    end = boundary;
                }
            }
            String piece = normalized.substring(start, end).strip();
            if (!piece.isEmpty()) {
                chunks.add(piece);
            }
            if (end >= len) {
                break;
            }
            // Advance from the HARD edge by the fixed step (not the nudged edge) so overlap/step math stays
            // stable and the chunk count is deterministic regardless of where the cosmetic nudge landed.
            start = Math.max(start + step, hardEnd - boundedOverlap);
            if (start < 0) {
                break;
            }
        }
        return chunks;
    }

    /**
     * The index of the last whitespace char at or before {@code end}, searching back at most
     * {@code lookback} chars; {@code -1} if none in range.
     */
    private static int lastWhitespaceWithin(String s, int end, int lookback) {
        int floor = Math.max(0, end - lookback);
        for (int i = end - 1; i >= floor; i--) {
            if (Character.isWhitespace(s.charAt(i))) {
                return i;
            }
        }
        return -1;
    }
}
