package com.kumouri.kmodigipresbe.module.frontdesk.reviews;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * FrontDesk IQ (FD-4 — HIPAA-safe review-reply) — the <strong>deterministic HIPAA lint</strong>, the second
 * of the two guardrail layers (FrontDesk IQ plan fence F4, the RE-4 {@code FairHousingLint} / FD-2 F3-lint
 * pattern).
 *
 * <p>The first layer is the generation system prompt ({@link FrontDeskReviewReplyService#HEALTH_DEFAULT_SYSTEM_PROMPT}),
 * which hard-forbids confirming the reviewer was a patient and naming any procedure/treatment/diagnosis/
 * medication. This lint is the auditable backstop: a pure, deterministic keyword/phrase scan over the
 * <em>drafted</em> reply that surfaces residual PHI-disclosure risk to the staffer who approves it. It gives
 * a defensible compliance story ("the system actively screens every public reply for patient-status
 * confirmation and clinical terms, and requires human sign-off") and a visible "N phrases flagged" signal —
 * but, exactly like the Fair-Housing lint, it <strong>does not block</strong> the draft from human review.
 * The lint + the mandatory human approval (the draft is never auto-posted) are the gate; the staffer decides.
 *
 * <p>Two banned-phrase families, both targeting a public-reply HIPAA disclosure (a real, common violation —
 * a reply that confirms care or identifies the reviewer as a patient is itself a disclosure of PHI):
 * <ul>
 *   <li><strong>Patient-status confirmation</strong> — phrases that confirm or imply the reviewer is/was a
 *       patient of the practice ("thank you for being our patient", "thank you for choosing our practice",
 *       "your treatment", "your procedure", "your appointment", "during your visit", "your care", "your
 *       results"). A public "thank you for being our patient" tells the world this person received care here.</li>
 *   <li><strong>Clinical vocabulary</strong> — a procedure / treatment / diagnosis / medication lexicon
 *       (crown, root canal, filling, extraction, cleaning, surgery, biopsy, x-ray, prescription, diagnosis,
 *       chemo, etc.) that names the care, which a HIPAA-safe public reply must never do.</li>
 * </ul>
 *
 * <p>It is a case-insensitive whole-token (for short single words) / phrase-substring (for multi-word
 * phrases) match — intentionally conservative (a few false positives a staffer can dismiss are far cheaper
 * than a missed disclosure). It is NOT an exhaustive compliance engine; the staffer remains responsible for
 * the published content. Stateless + side-effect-free → no Spring bean (a plain utility the
 * {@link FrontDeskReviewReplyService} calls), keeping the module's bean graph minimal (the {@code FairHousingLint}
 * posture).
 */
public final class HipaaReplyLint {

    /**
     * Patient-status-confirmation phrases (lower-case). A public reply containing any of these confirms or
     * strongly implies the reviewer received care at the practice — itself a PHI disclosure. All are
     * multi-word phrases (substring match), so they need no word-boundary handling.
     */
    static final List<String> PATIENT_STATUS_PHRASES = List.of(
            "being our patient", "being a patient", "your visit to our", "as a patient",
            "thank you for being our patient", "thank you for being a patient",
            "thank you for choosing our practice", "thank you for choosing our office",
            "thank you for trusting us with your care", "thank you for visiting our practice",
            "thank you for visiting our office", "since your treatment", "since your procedure",
            "after your treatment", "after your procedure", "after your surgery", "after your appointment",
            "during your treatment", "during your procedure", "during your appointment", "during your visit",
            "your treatment", "your procedure", "your surgery", "your diagnosis", "your appointment",
            "your last visit", "your recent visit", "your care with us", "your treatment plan",
            "your prescription", "your medication", "your test results", "your results", "your follow-up",
            "your operation", "your exam", "your cleaning", "your extraction", "your filling",
            "your root canal", "your crown", "your implant", "your checkup", "your check-up");

    /**
     * Clinical vocabulary (lower-case): procedures, treatments, diagnoses, and medications a HIPAA-safe
     * public reply must never name. Whole-word matched for the short single tokens (so "crown" does not
     * match inside "crowned" — handled by the word-boundary check); multi-word entries are substring-matched.
     * Deliberately broad across dental / medical / vet care; a staffer can dismiss a false positive.
     */
    static final List<String> CLINICAL_TERMS = List.of(
            // dental
            "crown", "root canal", "filling", "extraction", "cleaning", "implant", "veneer", "veneers",
            "denture", "dentures", "braces", "whitening", "cavity", "cavities", "gum", "wisdom tooth",
            // procedures / surgery
            "procedure", "surgery", "surgical", "operation", "biopsy", "anesthesia", "anesthetic",
            "stitches", "sutures", "incision", "transplant",
            // imaging / labs / dx
            "x-ray", "xray", "mri", "ct scan", "ultrasound", "bloodwork", "blood work", "lab results",
            "diagnosis", "diagnosed", "screening", "mammogram", "colonoscopy", "pathology",
            // meds / Rx
            "prescription", "medication", "medications", "antibiotic", "antibiotics", "painkiller",
            "painkillers", "opioid", "insulin", "chemo", "chemotherapy", "radiation", "dosage", "refill",
            // conditions / specialties that would identify care
            "cancer", "tumor", "infection", "fracture", "vaccine", "vaccination", "spay", "neuter",
            "oncology", "cardiology", "dermatology", "orthodontic", "orthodontics", "periodontal");

    private HipaaReplyLint() {
    }

    /**
     * One flagged HIPAA-risk term found in a drafted reply.
     *
     * @param category whether the match is a {@code PATIENT_STATUS} confirmation or a {@code CLINICAL} term
     * @param term     the matched banned term/phrase (lower-case)
     * @param snippet  a short window of the original (case-preserved) draft around the match, for the staffer
     */
    public record HipaaFlag(Category category, String term, String snippet) {
        public enum Category { PATIENT_STATUS, CLINICAL }
    }

    /**
     * Lints a drafted reply, returning one flag per banned term/phrase found. A {@code null}/blank draft
     * yields no flags. The scan is case-insensitive; single-word clinical tokens use a word-boundary check
     * (so "gum" does not match inside "chewing gum"... well, it would — but inside "argument" it would not),
     * multi-word phrases use a plain substring scan.
     *
     * @param draft the drafted reply text to scan
     * @return the flags (possibly empty), one per matched banned term — never blocks, only surfaces
     */
    public static List<HipaaFlag> lint(String draft) {
        List<HipaaFlag> flags = new ArrayList<>();
        if (draft == null || draft.isBlank()) {
            return flags;
        }
        String haystack = draft.toLowerCase(Locale.ROOT);
        for (String phrase : PATIENT_STATUS_PHRASES) {
            int idx = indexOfTerm(haystack, phrase);
            if (idx >= 0) {
                flags.add(new HipaaFlag(HipaaFlag.Category.PATIENT_STATUS, phrase,
                        snippet(draft, idx, phrase.length())));
            }
        }
        for (String term : CLINICAL_TERMS) {
            int idx = indexOfTerm(haystack, term);
            if (idx >= 0) {
                flags.add(new HipaaFlag(HipaaFlag.Category.CLINICAL, term,
                        snippet(draft, idx, term.length())));
            }
        }
        return flags;
    }

    /** Convenience: {@code true} iff the draft is clean (no patient-status / clinical flags). */
    public static boolean isClean(String draft) {
        return lint(draft).isEmpty();
    }

    /**
     * Index of {@code term} in {@code haystack} (already lower-cased), or -1. For a single-word term the
     * surrounding characters must be non-letters (word boundary); multi-word phrases match as a plain
     * substring. Mirrors {@code FairHousingLint.indexOfTerm}.
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

    /** A short window of the original (case-preserved) text around the match, for the staffer to read. */
    private static String snippet(String original, int idx, int termLen) {
        int start = Math.max(0, idx - 24);
        int end = Math.min(original.length(), idx + termLen + 24);
        String core = original.substring(start, end).trim();
        return (start > 0 ? "…" : "") + core + (end < original.length() ? "…" : "");
    }
}
