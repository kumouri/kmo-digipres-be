package com.kumouri.kmodigipresbe.model.inbox;

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
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Document("inbox_messages")
@CompoundIndex(name = "tenant_thread_idx",
        def = "{ 'tenantId': 1, 'threadId': 1, 'receivedAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class InboxMessage implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;
    private UUID threadId;

    /**
     * RFC 5322 Message-ID header. Used for IMAP-poller idempotency (Phase H.3):
     * a message whose Message-ID is already recorded is skipped. Nullable for
     * backward-compatibility with messages ingested before H.3 (additive-only —
     * the Phase-E {@code paymentTerms} precedent; no {@code @Builder.Default}).
     */
    @Indexed(sparse = true)
    private String messageId;

    private String from;
    @Builder.Default
    private List<String> to = List.of();
    private String subject;
    private String htmlBody;
    private String textBody;

    private Instant receivedAt;

    /** Best-effort match to a known {@code Contact.id} by sender email. */
    private UUID contactId;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
