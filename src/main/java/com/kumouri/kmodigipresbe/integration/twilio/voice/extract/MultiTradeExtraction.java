package com.kumouri.kmodigipresbe.integration.twilio.voice.extract;

/**
 * The structured multi-trade lead fields extracted from a home-services voicemail transcript by
 * {@link MultiTradeExtractionStrategy} (HS-1 — Home Services "Front Desk That Never Sleeps"). The
 * Phase-1 mole {@code VoicemailExtraction} is untouched; this is the additive home-services schema
 * (plan §2).
 *
 * <p>Every field is nullable/blank-tolerant — the LLM may not find a value, and a garbled
 * after-hours transcript must never fail the ingest (AI is triage, not truth — plan §8; the raw
 * transcript + recording are always attached). The three constrained-vocabulary fields
 * ({@link #trade}/{@link #urgency}/{@link #jobValueBand}) are parsed defensively into Java enums via
 * each enum's {@code fromWire} (a blank/garbage value degrades to {@code null}, never throws —
 * the {@code MoleClassificationCategory.fromWire} posture).
 *
 * @param name              the caller's name, if stated (nullable)
 * @param phone             a callback number stated in the message (nullable; the Twilio
 *                          {@code From} caller-ID is captured independently)
 * @param address           the service address, if stated (nullable)
 * @param trade             the trade discipline (nullable; {@code null} when unstated/garbled —
 *                          the strategy falls the WorkOrder back to {@code GENERAL})
 * @param urgency           the routing urgency (nullable)
 * @param symptom           a short problem description, e.g. "no heat, furnace clicking" (nullable)
 * @param jobValueBand      a coarse $-band triage hint (nullable)
 * @param callbackRequested whether the caller asked for a callback (defaults false)
 */
public record MultiTradeExtraction(
        String name,
        String phone,
        String address,
        Trade trade,
        Urgency urgency,
        String symptom,
        JobValueBand jobValueBand,
        boolean callbackRequested) {

    /** An all-empty extraction — used when the transcript is blank or extraction fails soft. */
    public static MultiTradeExtraction empty() {
        return new MultiTradeExtraction(null, null, null, null, null, null, null, false);
    }

    /**
     * The trade discipline the job falls under. {@code GENERAL} is both an explicit catch-all the
     * model may pick and the strategy's fallback when {@link #trade} is {@code null} (unstated or
     * garbled) — so a lead is never dropped for lack of a clean trade.
     */
    public enum Trade {
        HVAC, PLUMBING, ELECTRICAL, ROOFING, PEST, GENERAL;

        /**
         * Maps the model's wire label (case-insensitive) to a {@link Trade}. Null/blank/unrecognized
         * → {@code null} (never throws — the defensive-parse contract); the strategy decides the
         * fallback ({@code GENERAL}).
         */
        public static Trade fromWire(String wire) {
            if (wire == null || wire.isBlank()) {
                return null;
            }
            return switch (wire.trim().toUpperCase()) {
                case "HVAC" -> HVAC;
                case "PLUMBING" -> PLUMBING;
                case "ELECTRICAL" -> ELECTRICAL;
                case "ROOFING" -> ROOFING;
                case "PEST" -> PEST;
                case "GENERAL" -> GENERAL;
                default -> null;
            };
        }

        /** The wire label (for WorkOrder.serviceType / payloads / summaries). */
        public String wire() {
            return name();
        }
    }

    /**
     * Routing urgency. {@code EMERGENCY} is the routing key for HS-3's live-forward (recorded here,
     * not acted on in HS-1); {@code URGENT}/{@code ROUTINE} land on the Missed-Call Inbox. Null when
     * unstated/garbled.
     */
    public enum Urgency {
        EMERGENCY, URGENT, ROUTINE;

        /** Case-insensitive wire map; null/blank/unrecognized → {@code null} (never throws). */
        public static Urgency fromWire(String wire) {
            if (wire == null || wire.isBlank()) {
                return null;
            }
            return switch (wire.trim().toUpperCase()) {
                case "EMERGENCY" -> EMERGENCY;
                case "URGENT" -> URGENT;
                case "ROUTINE" -> ROUTINE;
                default -> null;
            };
        }

        public String wire() {
            return name();
        }
    }

    /** A coarse $-band triage prioritization hint. Null when unstated/garbled. */
    public enum JobValueBand {
        SMALL, MEDIUM, LARGE;

        /** Case-insensitive wire map; null/blank/unrecognized → {@code null} (never throws). */
        public static JobValueBand fromWire(String wire) {
            if (wire == null || wire.isBlank()) {
                return null;
            }
            return switch (wire.trim().toUpperCase()) {
                case "SMALL" -> SMALL;
                case "MEDIUM" -> MEDIUM;
                case "LARGE" -> LARGE;
                default -> null;
            };
        }

        public String wire() {
            return name();
        }
    }
}
