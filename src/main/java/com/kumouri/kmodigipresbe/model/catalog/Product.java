package com.kumouri.kmodigipresbe.model.catalog;

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
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Sellable product or service. The base primitive for Phase 7 quotes/invoices and
 * for future vertical catalogs (restaurant menu items, e-commerce SKUs) per the
 * plan — those modules will add their own {@code @Document} subtypes alongside
 * this one rather than crowding the core model.
 *
 * <p>{@code sku} is unique per tenant so quotes can reference products by SKU
 * without ambiguity. {@code unitPrice} is the default; price-list overrides
 * (volume discounts, customer-tier pricing) live on {@link PriceList}.
 */
@Document("products")
@CompoundIndex(name = "tenant_sku_idx", def = "{ 'tenantId': 1, 'sku': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Product implements TenantScoped, CustomFieldHost {

    @Id
    private UUID id;

    private UUID tenantId;

    private String sku;
    private String name;
    private String description;

    private BigDecimal unitPrice;

    /**
     * The unit <em>cost</em> to the business (what the salon/retailer pays), used by the T9 Salon
     * "StyleConsult AI" {@code StyleRecommendationService} to rank retail recommendations
     * <strong>margin-aware</strong> ({@code margin = unitPrice − unitCost}). Strictly additive +
     * nullable (the {@code Booking.noShowRisk} additive-nullable precedent): null for legacy products,
     * for products whose cost is unknown, and for tenants not using StyleConsult — such products are
     * treated as zero/unknown margin and ranked last. The core catalog CRUD never requires it and is
     * otherwise byte-identical; it is purely an advisory pricing input for the recommendation ranker.
     */
    private BigDecimal unitCost;

    @Builder.Default
    private String currency = "USD";

    private String unitOfMeasure;

    @Builder.Default
    private ProductType type = ProductType.GOOD;

    @Builder.Default
    private boolean active = true;

    @Builder.Default
    private Map<String, Object> customFields = Map.of();

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum ProductType { GOOD, SERVICE, SUBSCRIPTION }
}
