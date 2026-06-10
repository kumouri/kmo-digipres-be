package com.kumouri.kmodigipresbe.module.styleconsult.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * T9 (Salon "StyleConsult AI") — the hair/style attributes a service + retail recommendation is
 * composed from, whether typed by the prospect ({@code source=MANUAL}) or read off the inspiration
 * photo by the shared {@code AiVisionService.extract} ({@code source=VISION}). The
 * {@code StyleConsultVisionService} (S1) defensively parses the open-schema vision answer into this
 * carrier; {@code StyleRecommendationService} (S2) turns it into service + margin-aware retail
 * suggestions.
 *
 * <p>The salon-flavored twin of the T8 {@code QuoteAttributes} — same defensive-parse / confidence /
 * manual-wins-merge shape, but for hair attributes. Every field is nullable/blank-tolerant (an
 * inspiration photo may show only part of the look, the prospect may not describe everything, and the
 * vision answer is open-schema → degrades to an empty object on any failure). A read where nothing is
 * legible still produces a consult — AI is triage, not truth; a human stylist confirms.
 *
 * @param styleCategory the look the prospect wants (e.g. "balayage", "blonde highlights", "bob cut",
 *                      "curls", "keratin smoothing"), nullable — the primary service-mapping signal
 * @param length        the hair length (e.g. "short", "medium", "long"), nullable
 * @param texture       the hair texture (e.g. "straight", "wavy", "curly", "coily"), nullable
 * @param color         the current/target color (e.g. "brunette", "blonde", "balayage"), nullable
 * @param source        MANUAL or VISION — provenance
 * @param confidence    0.0–1.0 read confidence (VISION reflects how many fields came back; MANUAL=1.0)
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StyleAttributes {

    private String styleCategory;
    private String length;
    private String texture;
    private String color;

    @Builder.Default
    private StyleAttributeSource source = StyleAttributeSource.MANUAL;

    /** Read confidence in [0,1]; VISION reflects how many fields came back, MANUAL is 1.0. */
    @Builder.Default
    private double confidence = 1.0;

    /** True iff nothing usable was provided — every field is null/blank. A generic consult. */
    public boolean isEmpty() {
        return blank(styleCategory) && blank(length) && blank(texture) && blank(color);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Defensive parse of a raw vision {@link JsonNode} (open schema, already degraded to an empty
     * object on any upstream/parse failure) into VISION-sourced attributes. Reads {@code styleCategory}
     * (or {@code style}), {@code length}, {@code texture}, and {@code color}. Confidence is the
     * fraction of the four style-relevant fields that came back non-null. Never throws.
     */
    public static StyleAttributes fromVisionJson(JsonNode node) {
        if (node == null) {
            return StyleAttributes.builder().source(StyleAttributeSource.VISION).confidence(0.0).build();
        }
        String styleCategory = firstTextOrNull(node, "styleCategory", "style");
        String length = textOrNull(node, "length");
        String texture = textOrNull(node, "texture");
        String color = textOrNull(node, "color");

        int present = 0;
        if (!blank(styleCategory)) present++;
        if (!blank(length)) present++;
        if (!blank(texture)) present++;
        if (!blank(color)) present++;
        double confidence = present / 4.0;

        return StyleAttributes.builder()
                .styleCategory(styleCategory)
                .length(length)
                .texture(texture)
                .color(color)
                .source(StyleAttributeSource.VISION)
                .confidence(confidence)
                .build();
    }

    /**
     * Merge a vision read with any prospect-typed manual attributes: a non-blank manual field WINS
     * over the vision read (the prospect knows the exact look they're describing). The merged
     * provenance + confidence stay the vision read's (manual fields are certain).
     */
    public StyleAttributes mergedWithManual(StyleAttributes manual) {
        if (manual == null || manual.isEmpty()) {
            return this;
        }
        String styleCategory = !blank(manual.styleCategory) ? manual.styleCategory : this.styleCategory;
        String length = !blank(manual.length) ? manual.length : this.length;
        String texture = !blank(manual.texture) ? manual.texture : this.texture;
        String color = !blank(manual.color) ? manual.color : this.color;
        return StyleAttributes.builder()
                .styleCategory(styleCategory)
                .length(length)
                .texture(texture)
                .color(color)
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
}
