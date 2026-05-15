package com.kumouri.kmodigipresbe.module.salonspa.model;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
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
 * Append-only ledger entry for a {@link LoyaltyAccount}. Written on every point
 * accrual or redemption. Not {@link com.kumouri.kmodigipresbe.audit.Auditable}
 * (the log itself is the audit trail; auditing an append-only log creates no value).
 */
@Document("salon_loyalty_txns")
@CompoundIndex(name = "tenant_account_idx", def = "{ 'tenantId': 1, 'accountId': 1 }")
@CompoundIndex(name = "tenant_account_created_idx", def = "{ 'tenantId': 1, 'accountId': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyTransaction implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID accountId;

    /** Positive for accrual, negative for redemption. */
    private int delta;

    /** Machine-readable reason: e.g. {@code "VISIT_COMPLETED"}, {@code "TIER_BONUS"}, {@code "REDEMPTION"}. */
    private String reason;

    /** Invoice that triggered this transaction; null for manual adjustments. */
    private UUID invoiceId;

    @CreatedDate
    private Instant createdAt;
}
