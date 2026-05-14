package com.kumouri.kmodigipresbe.integration;

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
import java.util.Map;
import java.util.UUID;

/**
 * Per-tenant credentials and config for one external integration. The
 * {@code provider} string keys into integration-specific code (Stripe, Twilio,
 * Google Calendar, QuickBooks, ...). One row per (tenant, provider) — a tenant
 * can connect to each provider exactly once for Phase 8.
 *
 * <p>{@code secrets} is a free-form map for provider-specific credentials:
 * Stripe's {@code apiKey} + {@code webhookSigningSecret}, Twilio's
 * {@code accountSid} + {@code authToken} + {@code fromNumber}, OAuth2 access
 * + refresh tokens for Google/QuickBooks. Stored plaintext in Phase 8;
 * encrypt-at-rest is a hardening pass before any non-KMOSF tenant.
 */
@Document("integration_connections")
@CompoundIndex(name = "tenant_provider_idx",
        def = "{ 'tenantId': 1, 'provider': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationConnection implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String provider;
    private String displayName;

    @Builder.Default
    private Status status = Status.ACTIVE;

    @Builder.Default
    private Map<String, String> secrets = Map.of();

    @Builder.Default
    private Map<String, String> config = Map.of();

    private Instant connectedAt;
    private Instant lastUsedAt;
    private String lastErrorMessage;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { ACTIVE, DISCONNECTED, ERROR }
}
