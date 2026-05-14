package com.kumouri.kmodigipresbe.model.catalog;

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
import java.util.List;
import java.util.UUID;

/**
 * Named override of a tenant's product prices. Quotes pick one PriceList and look
 * up each line item's price through it; if a product isn't listed, the product's
 * {@code unitPrice} is the fallback.
 *
 * <p>The {@code entries} list is denormalized onto the document. With at most a
 * few hundred products per tenant, the read-side cost of scanning the list for a
 * match is negligible and avoids a second collection.
 */
@Document("price_lists")
@CompoundIndex(name = "tenant_name_idx", def = "{ 'tenantId': 1, 'name': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class PriceList implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private String name;
    private String description;

    @Builder.Default
    private String currency = "USD";

    @Builder.Default
    private List<Entry> entries = List.of();

    @Builder.Default
    private boolean active = true;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Entry {
        private UUID productId;
        private BigDecimal unitPrice;
    }
}
