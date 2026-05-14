package com.kumouri.kmodigipresbe.audit;

/**
 * A single property change recorded on an {@link AuditEvent}. {@code before} is the
 * value loaded from Mongo just prior to the save; {@code after} is the value on the
 * incoming entity. Either side may be {@code null} (e.g. a previously-unset field
 * being assigned, or vice versa). Both sides are typed {@code Object} because
 * domain entities carry mixed-type properties — strings, enums, BigDecimal, embedded
 * lists, custom-field maps, etc. — and Mongo's default converter handles all of these.
 */
public record FieldDiff(String field, Object before, Object after) {

    public FieldDiff {
        if (field == null || field.isBlank()) {
            throw new IllegalArgumentException("FieldDiff.field is required");
        }
    }
}
