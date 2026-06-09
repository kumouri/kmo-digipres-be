package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T4 (Health "Switchboard AI") — the per-tenant <strong>logistics answer book</strong> the
 * {@link LogisticsIntentHandler} reads to answer a patient's front-desk question, and the per-tenant safe
 * tripwire reply. One row per tenant (unique {@code tenant_idx}); a tenant-scoped CRM entity
 * ({@code TenantScoped}).
 *
 * <h2>"Answered from per-tenant config, never hardcoded"</h2>
 * Every logistics answer the responder texts back is rendered from THIS row (the {@code ResponderConfig}
 * holds only the intent vocabulary; the actual hours/location/links/copy live here). A tenant with no
 * {@code SwitchboardConfig} row still gets a safe generic acknowledgement (the handler degrades) — but the
 * useful, specific answers come from here. <strong>Generic / PHI-free content only</strong> — no
 * procedure, provider, diagnosis, or patient-specific detail ever belongs in these fields.
 *
 * <p>{@code answerOverrides} lets an owner fully replace the rendered answer for any logistics intent
 * (keyed by the {@link SwitchboardIntents} intent name); when absent the handler renders from the typed
 * fields below.
 */
@Document("switchboard_configs")
@CompoundIndex(name = "tenant_idx", def = "{ 'tenantId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SwitchboardConfig implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** Free-text office hours, e.g. "Mon–Thu 8am–5pm, Fri 8am–2pm". */
    private String hoursText;

    /** Free-text location / address / directions, e.g. "123 Main St, Suite 200; parking in the rear". */
    private String locationText;

    /** Whether the practice is accepting new patients (drives the ACCEPTING_NEW_PATIENTS answer). */
    @Builder.Default
    private boolean acceptingNewPatients = true;

    /** Optional override copy for the accepting-new-patients answer (else a default is rendered). */
    private String acceptingNewPatientsText;

    /** How a patient books a new appointment (phone number / link / instruction). */
    private String bookingInstructions;

    /** How a patient reschedules or cancels (phone number / link / instruction). */
    private String rescheduleInstructions;

    /** A link to the new-patient intake / registration forms (the INTAKE_FORM answer). */
    private String intakeFormUrl;

    /** A link where a patient can leave a review (the REVIEW_REQUEST answer). */
    private String reviewLinkUrl;

    /** Optional per-intent fully-rendered answer overrides (keyed by the SwitchboardIntents intent name). */
    @Builder.Default
    private Map<String, String> answerOverrides = new HashMap<>();

    /**
     * The safe reply texted back to a patient whose message trips the clinical tripwire. When blank the
     * handler uses {@link SwitchboardRedaction#DEFAULT_SAFE_TRIPWIRE_REPLY}. <strong>Must stay
     * generic/PHI-free</strong> — never reference a condition or treatment.
     */
    private String safeTripwireReply;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
