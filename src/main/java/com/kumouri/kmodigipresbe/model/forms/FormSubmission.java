package com.kumouri.kmodigipresbe.model.forms;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Record of a public form submission. Append-only — not {@link com.kumouri.kmodigipresbe.audit.Auditable};
 * the submission itself is the audit record.
 */
@Document("form_submissions")
@CompoundIndex(name = "tenant_form_idx", def = "{ 'tenantId': 1, 'formId': 1, 'submittedAt': -1 }")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class FormSubmission implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID formId;

    /** All submitted field values keyed by field key. */
    @Builder.Default
    private Map<String, String> rawPayload = Map.of();

    /** Validated field values after type coercion. */
    @Builder.Default
    private Map<String, String> parsedFields = Map.of();

    /** Resolved or created contact; null if contact creation was skipped or failed. */
    private UUID contactId;

    /** UTM parameters captured from the originating landing page URL. */
    @Builder.Default
    private Map<String, String> utmParams = Map.of();

    @CreatedDate
    private Instant submittedAt;
}
