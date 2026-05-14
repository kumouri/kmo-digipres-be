package com.kumouri.kmodigipresbe.model.sequence;

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
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One contact's progress through a {@link Sequence}. {@code completedSteps} is
 * the idempotency cursor — the engine refuses to fire a step whose index is
 * already in this list. {@code nextFireAt} is set by {@code WAIT} step handling
 * to gate the engine's tick.
 *
 * <p>{@code lastMessageId} ties this enrollment to the Postmark MessageID of the
 * most recent {@code EMAIL_SEND} step, so {@code BRANCH} steps can look up
 * {@code EmailEngagement} records to evaluate their condition.
 */
@Document("sequence_enrollments")
@CompoundIndex(name = "tenant_status_fire_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'nextFireAt': 1 }")
@CompoundIndex(name = "tenant_sequence_contact_idx",
        def = "{ 'tenantId': 1, 'sequenceId': 1, 'contactId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class SequenceEnrollment implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID sequenceId;
    private UUID contactId;

    private Instant enrolledAt;
    private Instant nextFireAt;

    @Builder.Default
    private int currentStepIndex = 0;

    @Builder.Default
    private Status status = Status.ACTIVE;

    @Builder.Default
    private List<Integer> completedSteps = new ArrayList<>();

    /** Postmark MessageID of the most recent EMAIL_SEND step's send. */
    private String lastMessageId;

    /**
     * Denormalised at enroll time so the engine's EMAIL_SEND doesn't have to
     * round-trip the Contact's {@code emails} list (the embedded
     * {@code jakarta.mail.internet.InternetAddress} doesn't deserialise cleanly
     * through Spring Data Mongo's POJO codec in every test fixture). Re-enroll a
     * contact whose primary email address changes.
     */
    private String contactEmail;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { ACTIVE, PAUSED, COMPLETED, EXITED }
}
