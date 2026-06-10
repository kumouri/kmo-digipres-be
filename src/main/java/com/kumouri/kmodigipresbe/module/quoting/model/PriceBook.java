package com.kumouri.kmodigipresbe.module.quoting.model;

import com.kumouri.kmodigipresbe.audit.Auditable;
import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — a tenant's <strong>price book</strong>: the calibrated source the
 * instant-quote synthesis draws its ranges from. <strong>One book per tenant</strong> (unique
 * {@code tenant_idx}); its {@link #lineItems} are priced (equipment × {@link JobKind}) bands plus a
 * flat {@link #diagnosticVisitLow}/{@link #diagnosticVisitHigh} fee used when nothing priceable
 * matches (so a quote is always produced, never an error).
 *
 * <p>The price book is <strong>the wrong-number-liability fence</strong>: a synthesized range is
 * only as defensible as the book a human reviewed + seeded. Combined with the mandatory
 * {@code QuoteRange.estimateDisclaimer}, this is what makes instant quoting safe.
 *
 * <p>{@code TenantScoped} for isolation + {@code Auditable} (a staff-curated CRM artifact). Not a
 * {@code CustomFieldHost}.
 */
@Document("price_books")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PriceBook implements TenantScoped, Auditable {

    @Id
    private UUID id;

    @Indexed(name = "tenant_idx", unique = true)
    private UUID tenantId;

    /** Human label for the book (e.g. "Comfort Air HVAC — 2026 price book"); optional. */
    private String name;

    @Builder.Default
    private String currency = "USD";

    /** The priced (equipment × repair/replace) bands. */
    @Builder.Default
    private List<PriceBookLineItem> lineItems = List.of();

    /** Flat diagnostic-visit fee band — the graceful fallback when nothing priceable matches. */
    @Builder.Default
    private BigDecimal diagnosticVisitLow = new BigDecimal("89");

    @Builder.Default
    private BigDecimal diagnosticVisitHigh = new BigDecimal("149");

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
