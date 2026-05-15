package com.kumouri.kmodigipresbe.model.compliance;

import com.kumouri.kmodigipresbe.audit.Auditable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * One GDPR consent record for a contact + topic pair.
 *
 * <p>Consent is additive: each call to {@code recordConsent} creates a new record
 * (GRANTED). Withdrawal appends a WITHDRAWN record on top; history is preserved.
 * Callers should look at the most-recent record per topic to determine current
 * status.
 */
@Document("consent_records")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@CompoundIndex(name = "tenant_contact_topic_idx",
        def = "{ 'tenantId': 1, 'contactId': 1, 'topic': 1, 'recordedAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConsentRecord implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;
    private UUID contactId;

    /** The lawful basis under GDPR Article 6. */
    private String lawfulBasis;

    /** The subscription or processing topic — e.g. {@code "marketing-email"}. */
    private String topic;

    private ConsentStatus status;

    /** How this record was recorded — e.g. {@code "form-submission"}, {@code "staff-entry"}. */
    private String source;

    /** Requester IP for web-form submissions; null for staff-entered records. */
    private String ipAddress;

    private UUID actorUserId;

    private Instant recordedAt;

    /** Set when {@code status == WITHDRAWN}; null otherwise. */
    private Instant withdrawnAt;

    @CreatedDate
    private Instant createdAt;

    public enum ConsentStatus { GRANTED, WITHDRAWN }
}
