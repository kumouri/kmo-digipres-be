package com.kumouri.kmodigipresbe.model.nurture;

import com.kumouri.kmodigipresbe.audit.Auditable;
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
 * One contact's progress through a {@link NurtureCampaign} (E1 — Nurture / Cadence Engine).
 * Tenant-scoped CRM entity ({@code Auditable}).
 *
 * <p>{@code currentStepIndex} + the per-(enrollment, step) {@link NurtureSendLog} ledger are the
 * idempotency cursor; {@code nextFireAt} gates the runner's tick (the {@code SequenceEnrollment}
 * precedent). {@code appliedBackoffDays} accumulates each completed step's
 * {@link NurtureCadenceStep#backoffDays} so each successive no-reply gap grows.
 *
 * <p>Indexes:
 * <ul>
 *   <li>{@code tenant_status_fire_idx {tenantId,status,nextFireAt}} — the runner's due-poll index
 *       (the {@code SequenceEnrollment.tenant_status_fire_idx} precedent).</li>
 *   <li><strong>unique</strong> {@code tenant_campaign_contact_idx {tenantId,campaignId,contactId}} —
 *       one enrollment per contact per campaign; the segmentation find-or-enroll idempotency key (the
 *       {@code SequenceEnrollment.tenant_sequence_contact_idx} precedent).</li>
 * </ul>
 */
@Document("nurture_enrollments")
@CompoundIndex(name = "tenant_status_fire_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'nextFireAt': 1 }")
@CompoundIndex(name = "tenant_campaign_contact_idx",
        def = "{ 'tenantId': 1, 'campaignId': 1, 'contactId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class NurtureEnrollment implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID campaignId;
    private UUID contactId;

    /** The dormancy tier this contact was bucketed into at enroll time. */
    private DormancyBucket bucket;

    @Builder.Default
    private int currentStepIndex = 0;

    @Builder.Default
    private NurtureEnrollmentStatus status = NurtureEnrollmentStatus.ENROLLED;

    /** When the current step is due. Null/now ⇒ due immediately (a fresh ENROLLED row). */
    private Instant nextFireAt;

    /** Accumulated cadence backoff (days) from completed steps' {@code backoffDays}. */
    @Builder.Default
    private int appliedBackoffDays = 0;

    private Instant enrolledAt;
    private Instant lastTouchAt;
    private Instant repliedAt;

    /** Free-text reason when {@code status == EXITED} (e.g. "campaign inactive", "no reachable channel"). */
    private String exitedReason;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
