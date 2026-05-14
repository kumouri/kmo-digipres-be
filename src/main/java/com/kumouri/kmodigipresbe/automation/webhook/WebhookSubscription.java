package com.kumouri.kmodigipresbe.automation.webhook;

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
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Per-tenant outbound webhook endpoint registration. Used both by the explicit
 * "fan out on event" delivery path (every subscription matching the event type
 * gets a POST) and as a target for {@code OUTBOUND_WEBHOOK} rule actions via
 * {@code id}.
 *
 * <p>{@code secret} is used to HMAC-sign the payload — the receiver verifies via
 * the {@code X-KMOSF-Signature} header.
 */
@Document("webhook_subscriptions")
@CompoundIndex(name = "tenant_active_idx", def = "{ 'tenantId': 1, 'active': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class WebhookSubscription implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String url;

    /**
     * HMAC-SHA256 secret. Stored as-is; consider encrypting at rest in a later
     * phase.
     */
    private String secret;

    /**
     * Event-type strings (see {@link com.kumouri.kmodigipresbe.automation.DomainEventType}).
     * Empty list means "all events."
     */
    @Builder.Default
    private List<String> eventTypes = List.of();

    @Builder.Default
    private boolean active = true;

    private Instant lastDeliveredAt;
    private long deliveryCount;
    private long failureCount;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public boolean handles(String eventType) {
        return eventTypes == null || eventTypes.isEmpty() || Set.copyOf(eventTypes).contains(eventType);
    }
}
