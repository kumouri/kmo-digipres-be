package com.kumouri.kmodigipresbe.model.billing;

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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Document("payments")
@CompoundIndex(name = "tenant_invoice_idx", def = "{ 'tenantId': 1, 'invoiceId': 1, 'paidAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Payment implements Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID invoiceId;

    private BigDecimal amount;

    @Builder.Default
    private String currency = "USD";

    private Instant paidAt;

    private Method method;

    /**
     * External processor id (Stripe payment intent, ACH transaction ref, etc.).
     * Phase 8 writes this; Phase 7 supports manual entry.
     */
    private String externalRef;

    private String notes;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Method { CASH, CHECK, ACH, CARD, OTHER }
}
