package com.kumouri.kmodigipresbe.module.styleconsult.controller.dto;

import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;

import java.time.Instant;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — one row in the office consult-inbox list. A lean projection of a
 * {@link StyleConsult}: who, the read style, how many service/retail recommendations, and the status —
 * enough for a salon coordinator to triage at a glance. Never the raw entity / tenant-internal fields.
 *
 * @param consultId     the {@code StyleConsult} id (the detail endpoint takes it)
 * @param contactId     the prospect Contact (nullable)
 * @param contactPhone  the prospect phone (nullable)
 * @param styleCategory the read/typed style category (nullable)
 * @param serviceCount  how many services were recommended
 * @param retailCount   how many retail products were recommended (margin-ranked)
 * @param status        NEW / BOOKED
 * @param createdAt     when the prospect submitted
 */
public record StyleConsultInboxCard(
        UUID consultId,
        UUID contactId,
        String contactPhone,
        String styleCategory,
        int serviceCount,
        int retailCount,
        StyleConsultStatus status,
        Instant createdAt) {

    public static StyleConsultInboxCard from(StyleConsult c) {
        return new StyleConsultInboxCard(
                c.getId(),
                c.getContactId(),
                c.getContactPhone(),
                c.getAttributes() == null ? null : c.getAttributes().getStyleCategory(),
                c.getServiceRecommendations() == null ? 0 : c.getServiceRecommendations().size(),
                c.getRetailRecommendations() == null ? 0 : c.getRetailRecommendations().size(),
                c.getStatus(),
                c.getCreatedAt());
    }
}
