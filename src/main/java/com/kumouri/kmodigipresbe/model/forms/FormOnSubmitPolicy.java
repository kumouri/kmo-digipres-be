package com.kumouri.kmodigipresbe.model.forms;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

/**
 * Embedded policy describing what happens when a {@link FormDefinition} is submitted.
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FormOnSubmitPolicy {

    /** When true, a Contact is upserted from the submission's email field. */
    @Builder.Default
    private boolean createContact = true;

    /**
     * Sequence to enroll the submitter in. Null means no auto-enrollment.
     * The contact must be resolved ({@code createContact=true} or an existing match)
     * for enrollment to occur.
     */
    private UUID addToSequenceId;

    /** URL the public form widget redirects to after a successful submission. */
    private String redirectUrl;
}
