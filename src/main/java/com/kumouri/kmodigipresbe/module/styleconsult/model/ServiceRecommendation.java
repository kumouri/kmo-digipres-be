package com.kumouri.kmodigipresbe.module.styleconsult.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * T9 (Salon "StyleConsult AI") — a single recommended salon service, embedded on a {@link StyleConsult}.
 * Maps a {@link com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenuItem} the salon offers to
 * the prospect's read style attributes (S2 {@code StyleRecommendationService}). The {@code rationale}
 * always carries the central "your stylist will confirm" guardrail note — a suggestion, never an
 * auto-charge.
 *
 * @param serviceMenuItemId the stable id of the {@code ServiceMenuItem} (the accept path books it)
 * @param name              the service name snapshot
 * @param price             the menu price snapshot (nullable)
 * @param rationale         why this service was suggested (always includes the stylist-confirm note)
 */
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ServiceRecommendation {

    private String serviceMenuItemId;
    private String name;
    private BigDecimal price;
    private String rationale;
}
