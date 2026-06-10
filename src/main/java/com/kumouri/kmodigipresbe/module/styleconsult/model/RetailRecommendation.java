package com.kumouri.kmodigipresbe.module.styleconsult.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — a single recommended <strong>margin-aware</strong> retail product,
 * embedded on a {@link StyleConsult}. Maps a {@link com.kumouri.kmodigipresbe.model.catalog.Product}
 * the salon stocks to the prospect's read style attributes (S2 {@code StyleRecommendationService}),
 * <strong>ranked by margin</strong> ({@code marginAmount = unitPrice − unitCost}) so the stylist's
 * highest-margin take-home products surface first. The {@code rationale} always carries the central
 * "your stylist will confirm" guardrail note — a suggestion, never an auto-charge.
 *
 * @param productId    the catalog {@code Product} id
 * @param sku          the product SKU snapshot (nullable)
 * @param name         the product name snapshot
 * @param price        the retail unit price snapshot (nullable)
 * @param cost         the unit cost snapshot used for the margin (nullable — null ⇒ unknown, ranked last)
 * @param marginAmount {@code price − cost} (the ranking key; 0 when cost is null/unknown)
 * @param rationale    why this product was suggested (always includes the stylist-confirm note)
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class RetailRecommendation {

    private UUID productId;
    private String sku;
    private String name;
    private BigDecimal price;
    private BigDecimal cost;
    private BigDecimal marginAmount;
    private String rationale;
}
