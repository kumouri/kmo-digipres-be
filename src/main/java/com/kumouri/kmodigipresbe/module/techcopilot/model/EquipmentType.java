package com.kumouri.kmodigipresbe.module.techcopilot.model;

/**
 * Tech Copilot (T13) — the coarse equipment category a {@link TechDoc} (manual / SOP / spec sheet)
 * documents, and a citation/UI routing label.
 *
 * <p>Open by design (the {@code DisclosureType} pattern): anything that does not fit a specific category
 * is {@link #GENERAL}. The category is surfaced verbatim in a citation ("from the FURNACE manual …") and
 * may optionally narrow a tech's question, but retrieval is never <em>restricted</em> to one category —
 * a tech's question searches the whole corpus and the doc identity rides in the citation metadata (T13-D3).
 */
public enum EquipmentType {
    FURNACE,
    AC,
    HEAT_PUMP,
    BOILER,
    WATER_HEATER,
    THERMOSTAT,
    DUCTLESS_MINI_SPLIT,
    REFRIGERATION,
    GENERAL;

    /** Defensive parse of a wire string into a type — unknown/blank → {@link #GENERAL} (never throws). */
    public static EquipmentType fromWire(String wire) {
        if (wire == null || wire.isBlank()) {
            return GENERAL;
        }
        try {
            return valueOf(wire.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            return GENERAL;
        }
    }
}
