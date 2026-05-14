package com.kumouri.kmodigipresbe.model.quote;

import com.kumouri.kmodigipresbe.extension.CustomFieldHost;
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
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A priced proposal sent to a customer. Built from {@link LineItem}s; totals are
 * recomputed on every save via {@link #computeTotals()}.
 *
 * <p>Flow: DRAFT → SENT → ACCEPTED / DECLINED / EXPIRED. The terminal-state
 * transition typically generates an Invoice in Phase 7.
 */
@Document("quotes")
@CompoundIndex(name = "tenant_status_idx", def = "{ 'tenantId': 1, 'status': 1, 'issuedAt': -1 }")
@CompoundIndex(name = "tenant_deal_idx", def = "{ 'tenantId': 1, 'dealId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Quote implements TenantScoped, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private String quoteNumber;

    @Builder.Default
    private Status status = Status.DRAFT;

    private UUID dealId;
    private UUID contactId;
    private UUID companyId;
    private UUID priceListId;

    @Builder.Default
    private String currency = "USD";

    @Builder.Default
    private List<LineItem> lineItems = List.of();

    private BigDecimal subtotal;
    private BigDecimal discountTotal;
    private BigDecimal taxTotal;
    private BigDecimal total;

    private String notes;
    private String terms;

    private LocalDate issuedAt;
    private LocalDate expiresAt;
    private Instant statusChangedAt;

    private String pdfStorageRef;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum Status { DRAFT, SENT, ACCEPTED, DECLINED, EXPIRED }

    /**
     * Recomputes {@code subtotal}, {@code discountTotal}, {@code taxTotal},
     * {@code total} and each line's {@code lineTotal}. Idempotent.
     */
    public void computeTotals() {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal discountTotal = BigDecimal.ZERO;
        BigDecimal taxTotal = BigDecimal.ZERO;
        List<LineItem> mutated = new ArrayList<>();
        for (LineItem li : lineItems == null ? List.<LineItem>of() : lineItems) {
            BigDecimal qty = nz(li.getQuantity());
            BigDecimal unit = nz(li.getUnitPrice());
            BigDecimal gross = qty.multiply(unit);
            BigDecimal discount = gross.multiply(nz(li.getDiscountPercent()))
                    .divide(HUNDRED, 4, RoundingMode.HALF_UP);
            BigDecimal afterDiscount = gross.subtract(discount);
            BigDecimal tax = afterDiscount.multiply(nz(li.getTaxPercent()))
                    .divide(HUNDRED, 4, RoundingMode.HALF_UP);
            BigDecimal lineTotal = afterDiscount.add(tax).setScale(2, RoundingMode.HALF_UP);
            li.setLineTotal(lineTotal);
            mutated.add(li);
            subtotal = subtotal.add(gross);
            discountTotal = discountTotal.add(discount);
            taxTotal = taxTotal.add(tax);
        }
        this.lineItems = mutated;
        this.subtotal = subtotal.setScale(2, RoundingMode.HALF_UP);
        this.discountTotal = discountTotal.setScale(2, RoundingMode.HALF_UP);
        this.taxTotal = taxTotal.setScale(2, RoundingMode.HALF_UP);
        this.total = subtotal.subtract(discountTotal).add(taxTotal)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal b) {
        return b == null ? BigDecimal.ZERO : b;
    }

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
}
