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
import java.util.List;
import java.util.UUID;

/**
 * A multi-step drip / cadence definition. {@code steps} is an ordered list of
 * {@link SequenceStep} embedded documents; {@code SequenceEnrollment} tracks
 * which contact is at which step.
 */
@Document("sequences")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Sequence implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;

    @Builder.Default
    private Status status = Status.DRAFT;

    @Builder.Default
    private List<SequenceStep> steps = List.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { DRAFT, ACTIVE, ARCHIVED }
}
