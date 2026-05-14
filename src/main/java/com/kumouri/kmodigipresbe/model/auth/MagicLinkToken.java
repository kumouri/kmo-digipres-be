package com.kumouri.kmodigipresbe.model.auth;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * Short-lived single-use token for magic-link sign-in. Persisted with a SHA-256 hash
 * of the issued token; the raw value is only ever in the email body. The
 * {@code expiresAt} field carries a TTL index so Mongo expires stale rows on its own.
 */
@Document("magic_link_tokens")
@CompoundIndex(
        name = "tenant_token_hash_idx",
        def = "{ 'tenantId': 1, 'tokenHash': 1 }",
        unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class MagicLinkToken implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String email;

    private String tokenHash;

    @Indexed(expireAfter = "0s")
    private Instant expiresAt;

    private Instant redeemedAt;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;
}
