package com.kumouri.kmodigipresbe.model.forms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Embedded field definition within a {@link FormDefinition}.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FormField {

    /** Machine-readable key; used as the key in submission's parsedFields map. */
    private String key;

    /** Human-readable label shown in the rendered form. */
    private String label;

    /** Input type. */
    private FieldType type;

    private boolean required;

    /** Options for SELECT and CHECKBOX types. */
    @Builder.Default
    private List<String> options = List.of();

    /** Optional placeholder text. */
    private String placeholder;

    public enum FieldType {
        TEXT, EMAIL, PHONE, SELECT, CHECKBOX, TEXTAREA
    }
}
