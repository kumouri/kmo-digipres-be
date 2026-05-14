package com.kumouri.kmodigipresbe.extension;

import java.util.Map;

/**
 * Marks an entity that accepts tenant-defined custom fields. Implementors expose the
 * stored {@code customFields} map and identify themselves with an entity-type string
 * matched against {@link FieldDefinition#getEntityType()}.
 *
 * <p>The default {@link #getEntityType()} derives from the implementor's class name —
 * {@code Contact} &rarr; {@code "CONTACT"}, {@code WorkOrder} &rarr; {@code "WORKORDER"}.
 * Override the default if your entity needs a different stable identifier.
 */
public interface CustomFieldHost {

    Map<String, Object> getCustomFields();

    void setCustomFields(Map<String, Object> customFields);

    default String getEntityType() {
        return getClass().getSimpleName().toUpperCase();
    }
}
