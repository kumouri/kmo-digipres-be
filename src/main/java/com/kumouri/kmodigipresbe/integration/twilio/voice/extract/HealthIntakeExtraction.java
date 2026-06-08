package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

/**
 * The <strong>logistics-only</strong> fields extracted from a health-practice after-hours voicemail
 * transcript by {@link HealthFrontDeskExtractionStrategy} (FrontDesk IQ FD-3 — the health-practices
 * flagship). The Phase-1 mole {@code VoicemailExtraction} and the HS-1 {@link MultiTradeExtraction}
 * are untouched; this is the additive health-front-desk schema (flagship plan FD-3 §2).
 *
 * <h2>FD-3 fence F2 — PHI-free by construction</h2>
 * This record carries <strong>no clinical field</strong>: there is no symptom / diagnosis / procedure /
 * drug / dosage slot, so the spoken clinical detail of the voicemail ("I need my blood-pressure meds
 * refilled") cannot be captured here even if the model emitted it. A refill <em>request</em> is routed
 * as the {@link IntentBucket#PRESCRIPTION_REFILL_REQUEST} <em>logistics bucket</em> — the routing
 * bucket is captured; the drug name / dosage / clinical reason is not. The strategy's system prompt
 * additionally instructs the model to drop any clinical/symptom detail (the prompt is the last of the
 * fences, never the only one — the absent fields are the structural fence).
 *
 * <p>Every field is nullable/blank-tolerant — AI is triage, not truth (plan §8); the caller-ID
 * {@code From} is captured independently so a callback is never dropped. The constrained-vocabulary
 * {@link #intentBucket} is parsed defensively into a Java enum via {@link IntentBucket#fromWire}
 * (a blank/garbage value degrades to {@link IntentBucket#OTHER}, never throws).
 *
 * @param name              the caller's name, if stated (nullable)
 * @param callbackNumber    a callback number stated in the message (nullable; the Twilio {@code From}
 *                          caller-ID is captured independently)
 * @param intentBucket      the logistics routing bucket (never a diagnosis; never {@code null} — an
 *                          absent/garbled value degrades to {@link IntentBucket#OTHER})
 * @param callbackRequested whether the caller asked for a callback (defaults false)
 */
public record HealthIntakeExtraction(
        String name,
        String callbackNumber,
        IntentBucket intentBucket,
        boolean callbackRequested) {

    public HealthIntakeExtraction {
        // intentBucket is never null on the carrier — OTHER is the safe logistics default.
        intentBucket = intentBucket == null ? IntentBucket.OTHER : intentBucket;
    }

    /** An all-empty extraction — used when the transcript is blank or extraction fails soft. */
    public static HealthIntakeExtraction empty() {
        return new HealthIntakeExtraction(null, null, IntentBucket.OTHER, false);
    }

    /**
     * The logistics routing bucket for a health-practice voicemail. Each value is a <strong>scheduling
     * / front-desk logistics</strong> category, NOT a diagnosis — the deliberate ride-up-to-the-line of
     * FD-3 (plan §0). {@link #PRESCRIPTION_REFILL_REQUEST} routes a refill <em>request</em> to the right
     * desk; the drug name / dosage is never captured (fence F2). {@link #OTHER} is both an explicit
     * catch-all the model may pick and the strategy's fallback for an unstated/garbled value — so a
     * callback is never dropped for lack of a clean bucket.
     */
    public enum IntentBucket {
        SCHEDULING,
        BILLING,
        PRESCRIPTION_REFILL_REQUEST,
        GENERAL_CALLBACK,
        OTHER;

        /**
         * Maps the model's wire label (case-insensitive) to an {@link IntentBucket}. Null / blank /
         * unrecognized → {@link #OTHER} (never throws — the defensive-parse contract; the
         * {@code MoleClassificationCategory.fromWire} posture, but defaulting to a usable bucket rather
         * than {@code null} because the carrier always carries a non-null bucket).
         */
        public static IntentBucket fromWire(String wire) {
            if (wire == null || wire.isBlank()) {
                return OTHER;
            }
            return switch (wire.trim().toUpperCase()) {
                case "SCHEDULING" -> SCHEDULING;
                case "BILLING" -> BILLING;
                case "PRESCRIPTION_REFILL_REQUEST" -> PRESCRIPTION_REFILL_REQUEST;
                case "GENERAL_CALLBACK" -> GENERAL_CALLBACK;
                default -> OTHER;
            };
        }

        /** The wire label (for the Activity-payload {@code extractedJson} + summary). */
        public String wire() {
            return name();
        }

        /** A short human label for the callback summary one-liner (the Callback inbox card). */
        public String label() {
            return switch (this) {
                case SCHEDULING -> "Scheduling";
                case BILLING -> "Billing";
                case PRESCRIPTION_REFILL_REQUEST -> "Prescription refill";
                case GENERAL_CALLBACK -> "General callback";
                case OTHER -> "Callback";
            };
        }
    }
}
