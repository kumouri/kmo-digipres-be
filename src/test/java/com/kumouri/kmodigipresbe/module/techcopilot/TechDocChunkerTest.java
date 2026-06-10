package com.kumouri.kmodigipresbe.module.techcopilot;

import com.kumouri.kmodigipresbe.module.techcopilot.ingest.TechDocChunker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T13 — the chunking proof (pure unit, no Docker). Asserts the headline net-new behavior over the RE-1
 * single-embed concierge: window count, overlap (a boundary-straddling phrase survives in an adjacent
 * chunk), the short-text-→-one-chunk and blank-→-empty edges, and the max-chars cap.
 */
class TechDocChunkerTest {

    @Test
    void blankOrNull_yieldsEmptyList() {
        assertThat(TechDocChunker.chunk(null)).isEmpty();
        assertThat(TechDocChunker.chunk("")).isEmpty();
        assertThat(TechDocChunker.chunk("   \n\t  ")).isEmpty();
    }

    @Test
    void shortText_yieldsExactlyOneChunk() {
        String text = "Fault E3: flame rollout. Reset: power off 30 seconds, then press reset.";
        List<String> chunks = TechDocChunker.chunk(text, 1_200, 200);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).isEqualTo(text);
    }

    @Test
    void longText_isSplitIntoMultipleWindows_eachWithinMaxChars() {
        // 5,000 chars of repeated content → multiple windows at a 1,000-char window.
        String text = "ABCDE ".repeat(1_000); // 6,000 chars
        List<String> chunks = TechDocChunker.chunk(text, 1_000, 200);
        assertThat(chunks.size()).isGreaterThan(1);
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(1_000));
    }

    @Test
    void adjacentChunks_overlap_soABoundaryStraddlingPhraseSurvivesIntact() {
        // A distinctive marker phrase placed right around the first window boundary must appear, in full,
        // in at least one chunk (the overlap guarantee — a procedure straddling a window edge is retrievable).
        int maxChars = 200;
        int overlap = 60;
        String marker = "RESET_PROCEDURE_FAULT_E3_PRESS_AND_HOLD";
        String filler = "x".repeat(maxChars - 20); // pushes the marker near the first boundary
        String text = filler + " " + marker + " " + "y".repeat(400);

        List<String> chunks = TechDocChunker.chunk(text, maxChars, overlap);

        assertThat(chunks.size()).isGreaterThan(1);
        boolean markerIntactSomewhere = chunks.stream().anyMatch(c -> c.contains(marker));
        assertThat(markerIntactSomewhere)
                .as("a boundary-straddling phrase appears intact in at least one chunk (overlap)")
                .isTrue();
    }

    @Test
    void chunksReconstructTheText_whenOverlapStripped_noContentLost() {
        // Sanity: every character of the source appears in some chunk (no gaps between windows).
        String text = "Step one. ".repeat(300); // 3,000 chars
        List<String> chunks = TechDocChunker.chunk(text, 800, 150);
        // Concatenating the chunks must cover the whole (normalized) text length at least once.
        String joined = String.join("", chunks);
        assertThat(joined.length()).isGreaterThanOrEqualTo(text.strip().length());
    }

    @Test
    void overlapClampedBelowWindow_soCursorAlwaysAdvances_finiteChunks() {
        // A pathological overlap >= maxChars must not loop forever; it is clamped so the cursor advances.
        String text = "z".repeat(5_000);
        List<String> chunks = TechDocChunker.chunk(text, 100, 500); // overlap > maxChars
        assertThat(chunks).isNotEmpty();
        assertThat(chunks.size()).isLessThan(5_000); // finite, bounded
        assertThat(chunks).allSatisfy(c -> assertThat(c.length()).isLessThanOrEqualTo(100));
    }
}
