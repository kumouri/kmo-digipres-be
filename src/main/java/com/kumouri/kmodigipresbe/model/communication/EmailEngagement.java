package com.kumouri.kmodigipresbe.model.communication;

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
import java.util.Map;
import java.util.UUID;

/**
 * One Postmark engagement webhook event recorded against a contact's timeline.
 * {@code messageId} is Postmark's id from the original send; {@code contactId} is
 * resolved by either the {@code kmosf_contact_id} metadata round-tripped through
 * the send, or by reverse-lookup on the recipient address.
 *
 * <p>{@code payload} is the raw Postmark JSON minus credentials — kept for audit /
 * troubleshooting and as future-proofing when Postmark adds new fields.
 */
@Document("email_engagements")
@CompoundIndex(name = "tenant_contact_at_idx",
        def = "{ 'tenantId': 1, 'contactId': 1, 'eventAt': -1 }")
@CompoundIndex(name = "tenant_message_idx",
        def = "{ 'tenantId': 1, 'messageId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class EmailEngagement implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID contactId;

    /** Postmark MessageID for the originating send. */
    private String messageId;

    private EmailEngagementEvent event;

    private Instant eventAt;

    private String recipient;

    @Builder.Default
    private Map<String, Object> payload = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
