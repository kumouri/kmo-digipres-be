package com.kumouri.kmodigipresbe.module.realestate.marketing;

import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft.FairHousingFlag;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Real Estate Concierge (RE-4 — Marketing Studio) — the <strong>deterministic Fair-Housing lint</strong>,
 * the second of the two guardrail layers (RE-4 §5 decision 5).
 *
 * <p>The first layer is the generation system prompt (it forbids protected-class / steering /
 * discriminatory language). This lint is the auditable backstop: a pure, deterministic keyword/phrase
 * scan over the <em>generated</em> copy that surfaces residual Fair-Housing risk terms to the agent. It
 * gives a defensible compliance story ("the system actively screens for Fair-Housing risk and requires
 * human sign-off") and a visible "N phrases flagged" signal — but it <strong>does not block</strong> the
 * draft from human review (the agent decides; the mandatory human approval is the real gate, never an
 * auto-publish).
 *
 * <p>The banned-term list targets the FHA §3604(c) protected classes (race, color, religion, national
 * origin, sex, familial status, disability) and the classic steering phrases ("safe neighborhood",
 * "perfect for families", "ideal for a young couple", "great for kids", "exclusive", "no kids", etc.).
 * It is a case-insensitive whole-token / phrase substring match — intentionally conservative (a few false
 * positives an agent can dismiss are far cheaper than a missed steering phrase). It is NOT an exhaustive
 * compliance engine; the agent remains responsible for published content.
 *
 * <p>Stateless + side-effect-free → no Spring bean (a plain utility the {@code ListingMarketingService}
 * calls), keeping the module's bean graph minimal.
 */
public final class FairHousingLint {

    /**
     * Banned terms / phrases (lower-case). Whole-word for short ambiguous tokens (handled by the
     * word-boundary check), substring for multi-word steering phrases. Deliberately broad on the
     * classic steering language; an agent can dismiss a false positive.
     */
    static final List<String> BANNED_TERMS = List.of(
            // familial status / children
            "perfect for families", "perfect for a family", "ideal for families",
            "great for families", "family-friendly neighborhood", "great for kids",
            "perfect for kids", "no kids", "no children", "adults only", "adult community",
            "empty nesters", "perfect for a young couple", "ideal for a young couple",
            "mature couple", "singles", "bachelor",
            // race / color / national origin / religion
            "christian", "catholic", "jewish", "muslim", "church", "synagogue", "mosque",
            "hispanic", "latino", "asian", "caucasian", "white neighborhood",
            "black neighborhood", "ethnic", "integrated neighborhood", "exclusive neighborhood",
            // disability
            "able-bodied", "no wheelchairs", "not handicap accessible", "no disabilities",
            "perfect for the physically fit",
            // sex / gender
            "ideal for a single man", "ideal for a single woman", "gentleman's",
            // steering / safety code
            "safe neighborhood", "safe area", "safe community", "crime-free",
            "desirable neighborhood for", "restricted", "exclusive community");

    private FairHousingLint() {
    }

    /**
     * Lints one channel's generated copy, returning a flag per banned term found. A null/blank text
     * yields no flags. The match is case-insensitive; for short single-word terms a word-boundary check
     * avoids spurious substring hits (e.g. "singles" inside a larger word), while multi-word phrases use
     * a plain substring scan.
     *
     * @param channel the channel the {@code text} belongs to (stamped on each flag)
     * @param text    the generated copy to scan
     * @return the flags (possibly empty), one per matched banned term
     */
    public static List<FairHousingFlag> lint(MarketingChannel channel, String text) {
        List<FairHousingFlag> flags = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return flags;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String term : BANNED_TERMS) {
            int idx = indexOfTerm(haystack, term);
            if (idx >= 0) {
                flags.add(FairHousingFlag.builder()
                        .term(term)
                        .channel(channel)
                        .snippet(snippet(text, idx, term.length()))
                        .build());
            }
        }
        return flags;
    }

    /**
     * Channel-agnostic risk check over the same {@link #BANNED_TERMS} (security fix BE-12). Used
     * by the SMS concierge — which has no {@link MarketingChannel} — as the deterministic output
     * backstop before an AI answer is auto-sent: a {@code true} means the generated text contains
     * a Fair-Housing risk term, so the caller suppresses/hands off rather than sending it. Unlike
     * the marketing {@link #lint} (whose flags surface to a human reviewer who still approves),
     * the concierge auto-sends, so a positive here forces a safe outcome.
     *
     * @return the first matched banned term (lower-case) if the text carries Fair-Housing risk,
     *         else {@code null} (clean / blank).
     */
    public static String firstRiskTerm(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String haystack = text.toLowerCase(Locale.ROOT);
        for (String term : BANNED_TERMS) {
            if (indexOfTerm(haystack, term) >= 0) {
                return term;
            }
        }
        return null;
    }

    /**
     * Returns the index of {@code term} in {@code haystack} (already lower-cased), or -1. For a
     * single-word term the surrounding characters must be non-letters (word boundary) so "singles" does
     * not match inside, e.g., "shingles"; multi-word phrases match as a plain substring.
     */
    private static int indexOfTerm(String haystack, String term) {
        boolean singleWord = term.indexOf(' ') < 0 && term.indexOf('-') < 0;
        int from = 0;
        while (true) {
            int idx = haystack.indexOf(term, from);
            if (idx < 0) {
                return -1;
            }
            if (!singleWord || isWordBoundary(haystack, idx, term.length())) {
                return idx;
            }
            from = idx + 1;
        }
    }

    private static boolean isWordBoundary(String s, int start, int len) {
        int end = start + len;
        boolean leftOk = start == 0 || !Character.isLetter(s.charAt(start - 1));
        boolean rightOk = end >= s.length() || !Character.isLetter(s.charAt(end));
        return leftOk && rightOk;
    }

    /** A short window of the original (case-preserved) text around the match, for the agent to read. */
    private static String snippet(String original, int idx, int termLen) {
        int start = Math.max(0, idx - 24);
        int end = Math.min(original.length(), idx + termLen + 24);
        String core = original.substring(start, end).trim();
        return (start > 0 ? "…" : "") + core + (end < original.length() ? "…" : "");
    }
}
