package com.kumouri.kmodigipresbe.module.salonspa.model;

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
import java.util.UUID;

@Document("salon_loyalty_accounts")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }", unique = true)
@CompoundIndex(name = "tenant_tier_idx", def = "{ 'tenantId': 1, 'tier': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LoyaltyAccount implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID contactId;

    @Builder.Default
    private int points = 0;

    @Builder.Default
    private LoyaltyTier tier = LoyaltyTier.BRONZE;

    @Builder.Default
    private int visitCount = 0;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "LOYALTY_ACCOUNT";
    }
}
