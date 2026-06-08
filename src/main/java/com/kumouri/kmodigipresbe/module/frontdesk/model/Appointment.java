package com.kumouri.kmodigipresbe.module.frontdesk.model;

import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
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
import java.util.UUID;

/**
 * FrontDesk IQ (FD-1) — a thin, PHI-free appointment for a health practice (dental / medical / vet).
 *
 * <h2>The headline boundary: PHI-free by construction (fence F1)</h2>
 * <p>FrontDesk IQ's entire pitch is "a smarter front desk that never touches the chart." That boundary
 * is enforced <strong>at the data layer, here, by what this document physically cannot hold.</strong>
 * Every field below is <em>scheduling logistics metadata</em>. <strong>There is deliberately NO field for
 * a diagnosis, procedure, chief complaint, provider name, clinical note, or any free-text a patient spoke
 * about their condition.</strong> The no-show scorer ({@code FrontDeskNoShowScoringService}) builds its
 * feature vector only from these fields, so the model <em>literally cannot</em> see a clinical detail — a
 * reviewer can verify the PHI boundary by reading this class, not by arguing that no caller ever wrote PHI
 * into a free-text {@code notes} field (the reason a purpose-built document beats reusing salon
 * {@code Booking} / core {@code Meeting} — FrontDesk IQ plan §0 / D1).
 *
 * <p>A release-blocking integration test ({@code FrontDeskNoShowScoringServiceIT}) asserts via reflection
 * that no field on this class has a clinical name, so the boundary cannot drift open in a future change
 * (the "refuse the field, do not flag the field" discipline, FD-1 D4). Adding a {@code diagnosis} /
 * {@code procedure} / {@code chiefComplaint} / {@code clinicalNotes} field here is a deliberate
 * boundary violation that test exists to catch — that scope is the separately-priced, BAA-gated
 * compliance tier, NOT this build.
 *
 * <h2>Shape</h2>
 * <p>Modeled field-for-field on the <em>non-salon</em> parts of the salon {@code Booking} plus the
 * logistics signals the no-show model needs (insurance-verification-pending, reminder count, last-visit
 * timestamp for recall). It is {@code TenantScoped} for isolation and module-gated behind
 * {@code kmosf.modules.frontdesk.enabled}; the core CRM never reads or writes it (blast radius zero).
 *
 * <p>The demo seeds {@code Appointment} rows directly (the RE-3 deterministic-seeding posture); a live
 * deployment syncs them via the sparse {@link #calComBookingUid} + the shipped {@code CalComWebhookService}
 * projection, or via a <strong>non-PHI-columns-only</strong> CSV/PMS metadata export — no clinical column
 * is ever ingested (FD-1 §6 go-live).
 */
@Document("frontdesk_appointments")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@CompoundIndex(name = "tenant_provider_start_idx",
        def = "{ 'tenantId': 1, 'providerId': 1, 'scheduledStart': 1 }")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1 }")
// Non-unique lookup index for the future Cal.com reconcile path. NOT unique: a COMPOUND sparse/unique
// index is only sparse when ALL indexed fields are absent, but tenantId is always present, so every
// directly-seeded appointment (calComBookingUid = null) would collide on the null key and
// DuplicateKeyException the 2nd insert (the Tenant.zitadelOrgId compound-null lesson). FD-1 seeds rows
// directly and does no live Cal.com sync, so uniqueness is not needed yet; when FD-3 / live Cal.com sync
// lands, dedup-on-uid is enforced via a partialFilterExpression unique index (only docs WHERE the uid
// exists), not a compound-sparse one.
@CompoundIndex(name = "tenant_calcom_idx", def = "{ 'tenantId': 1, 'calComBookingUid': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Appointment implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The patient/contact this appointment is for (a CRM Contact id — logistics, no clinical content). */
    private UUID contactId;

    /**
     * Opaque staff/provider reference for scheduling and load. <strong>Never rendered into outbound
     * patient copy</strong> (fence F3) — it exists only to scope/sort the schedule, not to name a provider
     * in an SMS. An opaque id, not a name.
     */
    private UUID providerId;

    private Instant scheduledStart;

    private Instant scheduledEnd;

    @Builder.Default
    private AppointmentStatus status = AppointmentStatus.SCHEDULED;

    /**
     * The scheduling category (new-patient / recall / follow-up / hygiene / annual-wellness / other).
     * A closed logistics enum chosen by staff — NOT a diagnosis or procedure (fence F1). Consumed only as
     * a model ordinal by the scorer; never rendered into outbound copy (fence F3). See {@link VisitTypeBucket}.
     */
    @Builder.Default
    private VisitTypeBucket visitTypeBucket = VisitTypeBucket.OTHER;

    /**
     * Whether insurance verification is still outstanding for this appointment. A known no-show correlate
     * and pure scheduling logistics (no plan/policy/clinical detail is stored — just the boolean state).
     */
    @Builder.Default
    private boolean insuranceVerificationPending = false;

    /**
     * The contact's most-recent prior visit timestamp, used for recall cadence (FD-2) and the
     * days-since-last-visit feature. A metadata timestamp — NOT a clinical reason for return.
     */
    private Instant lastVisitAt;

    /** How many confirmations/reminders have already been sent for this appointment (an engagement proxy). */
    @Builder.Default
    private int reminderCount = 0;

    /**
     * Nightly no-show-risk score stamped by {@code FrontDeskNoShowScoringService} on <em>upcoming</em>
     * appointments only. Additive + nullable (the {@code Booking.noShowRisk} / {@code Contact.leadScore}
     * embed precedent): null for terminal appointments, for tenants without the {@code frontdesk} module,
     * and before the first scoring run.
     *
     * <p><strong>Reuses the chairfill {@link NoShowRisk} value type verbatim</strong> (the FD-1 D2 / plan
     * reuse decision — it is a domain-neutral {@code {riskScore, riskTier, source, computedAt}} record, and
     * the codebase's ArchUnit rules do not forbid a module → {@code chairfill.model} import; indeed
     * {@code RealEstateAutoConfiguration} already imports {@code chairfill}). CF-1's scorer is untouched —
     * the two scorers share this <em>type</em> but never share data (FrontDesk reads only
     * {@code AppointmentRepository}, never {@code BookingRepository}).
     */
    private NoShowRisk noShowRisk;

    /**
     * Cal.com booking uid for a live-sync deployment (RE-3/CF posture). Sparse + unique per tenant so the
     * shipped {@code CalComWebhookService} reconcile can be wired in later with no scorer change. Null for
     * directly-seeded demo appointments and CSV/PMS-imported ones.
     */
    private String calComBookingUid;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
