package com.kumouri.kmodigipresbe.integration.equipmentvision;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The structured equipment-nameplate fields read off a caller's photo by
 * {@link EquipmentVisionService} (HS-2 — Home Services "Front Desk That Never Sleeps"). The vision
 * model is asked to transcribe what is printed on the nameplate; this carrier is the defensively
 * parsed shape of that answer.
 *
 * <p>Every field is nullable/blank-tolerant — a nameplate may be partially occluded, weathered, or
 * the photo may not show one at all, and the vision answer is open-schema (the shared
 * {@link com.kumouri.kmodigipresbe.service.ai.vision.AiVisionService#extract} returns a raw
 * {@link JsonNode} that degrades to an empty object on any failure). <strong>AI is triage, not
 * truth</strong> (plan §8): the read enriches the DRAFT WorkOrder a dispatcher confirms — it never
 * gates or mutates the lead. A read where every field is blank is treated as "nothing legible" and
 * enriches nothing.
 *
 * @param make            the manufacturer printed on the nameplate (e.g. "Carrier"), nullable
 * @param model           the model number/name (e.g. "58STA"), nullable
 * @param serial          the serial number, nullable
 * @param equipmentType   what the unit is (e.g. "furnace", "condenser", "water heater"), nullable
 * @param observedSymptom any visible fault/condition the model can see in the photo (e.g. "rust on
 *                        heat exchanger", "tripped breaker"), nullable
 */
public record EquipmentReading(
        String make,
        String model,
        String serial,
        String equipmentType,
        String observedSymptom) {

    /** True iff the read found nothing legible — every field is null/blank. A no-op enrichment. */
    public boolean isEmpty() {
        return blank(make) && blank(model) && blank(serial)
                && blank(equipmentType) && blank(observedSymptom);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /**
     * Defensive parse of the raw {@link JsonNode} the vision transport returned (open schema, already
     * degraded to an empty object on any upstream/parse failure). Reads the five nameplate keys;
     * a missing/null/blank value becomes {@code null}. Never throws.
     */
    public static EquipmentReading fromJson(JsonNode node) {
        if (node == null) {
            return new EquipmentReading(null, null, null, null, null);
        }
        return new EquipmentReading(
                textOrNull(node, "make"),
                textOrNull(node, "model"),
                textOrNull(node, "serial"),
                textOrNull(node, "equipmentType"),
                textOrNull(node, "observedSymptom"));
    }

    /**
     * The nameplate fields as an insertion-ordered map (only non-blank entries), for stamping onto
     * the WorkOrder {@code customFields} / the owner-digest payload. Empty when {@link #isEmpty()}.
     */
    public Map<String, Object> toFieldMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        if (!blank(make)) m.put("equipmentMake", make.trim());
        if (!blank(model)) m.put("equipmentModel", model.trim());
        if (!blank(serial)) m.put("equipmentSerial", serial.trim());
        if (!blank(equipmentType)) m.put("equipmentType", equipmentType.trim());
        if (!blank(observedSymptom)) m.put("observedSymptom", observedSymptom.trim());
        return m;
    }

    /** A one-line human summary of the read ("Carrier 58STA, serial 1234 — furnace"), defensive. */
    public String toSummaryLine() {
        StringBuilder sb = new StringBuilder();
        if (!blank(make)) sb.append(make.trim());
        if (!blank(model)) sb.append(sb.length() > 0 ? " " : "").append(model.trim());
        if (!blank(serial)) sb.append(sb.length() > 0 ? ", serial " : "serial ").append(serial.trim());
        if (!blank(equipmentType)) {
            sb.append(sb.length() > 0 ? " — " : "").append(equipmentType.trim());
        }
        if (!blank(observedSymptom)) {
            sb.append(sb.length() > 0 ? " (" : "(").append(observedSymptom.trim()).append(")");
        }
        return sb.length() == 0 ? "(no legible nameplate)" : sb.toString();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode v = node.path(field);
        if (v.isMissingNode() || v.isNull()) return null;
        String s = v.asText(null);
        return (s == null || s.isBlank()) ? null : s;
    }
}
