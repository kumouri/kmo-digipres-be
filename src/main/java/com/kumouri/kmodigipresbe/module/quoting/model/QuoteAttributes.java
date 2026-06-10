package com.kumouri.kmodigipresbe.module.quoting.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * T8 (Home Services "QuoteNow") — the equipment/job attributes a price quote is synthesized from,
 * whether typed by the homeowner ({@code source=MANUAL}) or read off a photo by the shared
 * {@code AiVisionService.extract} ({@code source=VISION}). The {@code QuoteVisionService} (Q2)
 * defensively parses the open-schema vision answer into this carrier; {@code QuoteSynthesisService}
 * (Q1) turns it into a {@link QuoteRange}.
 *
 * <p>Every field is nullable/blank-tolerant — a nameplate may be partly occluded, the homeowner may
 * not know the age, and the vision answer is open-schema (degrades to an empty object on any
 * failure). A read where the type is unknown synthesizes a graceful diagnostic-visit range rather
 * than failing — the quote is always produced.
 *
 * @param equipmentType   the unit/job class the price book is keyed by (e.g. "condenser",
 *                        "furnace", "water heater"), nullable
 * @param brand           the manufacturer, nullable (advisory — not a pricing input v1)
 * @param ageYears        the unit's age in years (drives the age modifier + repair-vs-replace),
 *                        nullable
 * @param failureMode     the visible/described failure (e.g. "blowing warm", "leaking",
 *                        "not igniting"); drives the condition modifier + repair-vs-replace, nullable
 * @param source          MANUAL or VISION — provenance
 * @param confidence      0.0–1.0 read confidence (VISION only; how complete the read was), MANUAL=1.0
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class QuoteAttributes {

    private String equipmentType;
    private String brand;
    private Integer ageYears;
    private String failureMode;

    @Builder.Default
    private AttributeSource source = AttributeSource.MANUAL;

    /** Read confidence in [0,1]; VISION reflects how many fields came back, MANUAL is 1.0. */
    @Builder.Default
    private double confidence = 1.0;

    /** True iff nothing usable was provided — every field is null/blank. A diagnostic-visit quote. */
    public boolean isEmpty() {
        return blank(equipmentType) && blank(brand) && ageYears == null && blank(failureMode);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Defensive parse of a raw vision {@link JsonNode} (open schema, already degraded to an empty
     * object on any upstream/parse failure) into VISION-sourced attributes. Reads
     * {@code equipmentType}, {@code brand}, {@code ageEstimateYears} (or {@code ageYears}), and
     * {@code visibleFailureMode} (or {@code failureMode}). Confidence is the fraction of the four
     * pricing-relevant fields that came back non-null. Never throws.
     */
    public static QuoteAttributes fromVisionJson(JsonNode node) {
        if (node == null) {
            return QuoteAttributes.builder().source(AttributeSource.VISION).confidence(0.0).build();
        }
        String equipmentType = textOrNull(node, "equipmentType");
        String brand = textOrNull(node, "brand");
        Integer ageYears = intOrNull(node, "ageEstimateYears", "ageYears");
        String failureMode = firstTextOrNull(node, "visibleFailureMode", "failureMode");

        int present = 0;
        if (!blank(equipmentType)) present++;
        if (!blank(brand)) present++;
        if (ageYears != null) present++;
        if (!blank(failureMode)) present++;
        double confidence = present / 4.0;

        return QuoteAttributes.builder()
                .equipmentType(equipmentType)
                .brand(brand)
                .ageYears(ageYears)
                .failureMode(failureMode)
                .source(AttributeSource.VISION)
                .confidence(confidence)
                .build();
    }

    /**
     * Merge a vision read with any homeowner-typed manual attributes: a non-blank manual field WINS
     * over the vision read (the homeowner knows their unit's age better than a photo guess). The
     * merged provenance is VISION iff the vision read contributed at least one field the manual set
     * did not; the merged confidence is the vision confidence (manual fields are certain).
     */
    public QuoteAttributes mergedWithManual(QuoteAttributes manual) {
        if (manual == null || manual.isEmpty()) {
            return this;
        }
        String equipmentType = !blank(manual.equipmentType) ? manual.equipmentType : this.equipmentType;
        String brand = !blank(manual.brand) ? manual.brand : this.brand;
        Integer ageYears = manual.ageYears != null ? manual.ageYears : this.ageYears;
        String failureMode = !blank(manual.failureMode) ? manual.failureMode : this.failureMode;
        return QuoteAttributes.builder()
                .equipmentType(equipmentType)
                .brand(brand)
                .ageYears(ageYears)
                .failureMode(failureMode)
                .source(this.source)
                .confidence(this.confidence)
                .build();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }

    private static String firstTextOrNull(JsonNode node, String... fields) {
        for (String f : fields) {
            String s = textOrNull(node, f);
            if (s != null) return s;
        }
        return null;
    }

    private static Integer intOrNull(JsonNode node, String... fields) {
        for (String f : fields) {
            JsonNode v = node.path(f);
            if (v.isMissingNode() || v.isNull()) continue;
            if (v.isInt() || v.isLong()) return v.asInt();
            if (v.isNumber()) return (int) Math.round(v.asDouble());
            if (v.isTextual()) {
                String t = v.asText().trim();
                // tolerate "12", "12 years", "~12"
                StringBuilder digits = new StringBuilder();
                for (char c : t.toCharArray()) {
                    if (Character.isDigit(c)) digits.append(c);
                    else if (digits.length() > 0) break;
                }
                if (digits.length() > 0) {
                    try {
                        return Integer.parseInt(digits.toString());
                    } catch (NumberFormatException ignored) {
                        // fall through
                    }
                }
            }
        }
        return null;
    }
}
