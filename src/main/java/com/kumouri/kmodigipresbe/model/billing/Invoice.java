package com.kumouri.kmodigipresbe.model.billing;

import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
import com.kumouri.kmodigipresbe.model.quote.LineItem;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Schema-only for Phase 7 — Stripe integration in Phase 8 hooks
 * {@link #status} transitions and writes {@link Payment} entries. {@link #balance}
 * is recomputed from total − sum(payments) at read time (see the service).
 */
@Document("invoices")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1, 'issuedAt': -1 }")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@CompoundIndex(name = "tenant_number_idx", def = "{ 'tenantId': 1, 'invoiceNumber': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Invoice implements TenantScoped, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private String invoiceNumber;

    @Builder.Default
    private Status status = Status.DRAFT;

    private UUID quoteId;
    private UUID dealId;
    private UUID contactId;
    private UUID companyId;

    @Builder.Default
    private String currency = "USD";

    @Builder.Default
    private List<LineItem> lineItems = List.of();

    private BigDecimal subtotal;
    private BigDecimal discountTotal;
    private BigDecimal taxTotal;
    private BigDecimal total;

    /** Cached total − sum(payments). Recomputed by InvoiceService on read. */
    private BigDecimal balance;

    private LocalDate issuedAt;
    private LocalDate dueAt;
    private Instant statusChangedAt;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { DRAFT, SENT, PARTIALLY_PAID, PAID, VOIDED, OVERDUE }
}
