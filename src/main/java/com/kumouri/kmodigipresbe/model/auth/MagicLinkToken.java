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
 *
 * <p>The optional {@code redirectTo} field (G.5 — additive, nullable, default null) records
 * the deep-link target supplied at <em>request-time</em>. It is echoed back in the redeem
 * response so the portal FE can route post-login to the intended page (e.g.
 * {@code /portal/contracts/<id>}). A null value means no deep-link was supplied; legacy
 * tokens (pre-G.5) deserialise with {@code redirectTo=null} — behaviour is byte-identical
 * to before the addition.
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

    /**
     * Optional deep-link target persisted at request-time (G.5). Nullable; null means
     * no deep-link was requested. The value stored here is echoed verbatim in the
     * {@code MagicLinkRedemption} response — it is NEVER sourced from the redeem request
     * (open-redirect mitigation: §9 #5).
     */
    private String redirectTo;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;
}
