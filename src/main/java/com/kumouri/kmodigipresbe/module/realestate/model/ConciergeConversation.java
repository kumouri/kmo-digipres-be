package com.kumouri.kmodigipresbe.module.realestate.model;

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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-1) — the persisted state of one buyer↔concierge SMS thread for a
 * {@link Listing} (RE-1 §4 decision 2 / §6.2).
 *
 * <p>The shipped {@code InboundSmsService.route()} is a one-shot classifier; the concierge is multi-turn,
 * so it needs session state that the YES/STOP path lacks. RE-1 is a single-turn grounded Q&A but persists
 * this state so RE-2 (qualification → {@code Deal}) and RE-3 (showing booking) build on it without a
 * model migration. {@link #contactId} / {@link #dealId} stay null until RE-2 resolves/qualifies the buyer.
 *
 * <p>Correlation (RE-1 §6.6): primary = the listing's {@link Listing#getTrackedPhone() tracked number}
 * ({@code To}); fallback = the most-recent conversation for {@link #buyerPhone} ({@code From}) within a
 * TTL window when a tenant shares one number across listings. Compound indexes
 * {@code {tenantId, buyerPhone, listingId}} (find/create) and {@code {tenantId, listingId, lastInboundAt}}
 * (recency fallback).
 *
 * <p>The {@code @Version} field is the optimistic-lock backstop for rapid concurrent inbound texts on the
 * same thread (RE-1 §8 state-races mitigation). {@code TenantScoped} for isolation; not {@code Auditable}
 * (an automation/transcript record).
 */
@Document("re_concierge_conversations")
@CompoundIndex(name = "tenant_phone_listing_idx",
        def = "{ 'tenantId': 1, 'buyerPhone': 1, 'listingId': 1 }")
@CompoundIndex(name = "tenant_listing_recency_idx",
        def = "{ 'tenantId': 1, 'listingId': 1, 'lastInboundAt': -1 }")
@CompoundIndex(name = "tenant_phone_recency_idx",
        def = "{ 'tenantId': 1, 'buyerPhone': 1, 'lastInboundAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class ConciergeConversation implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    private UUID listingId;

    /** The buyer's contact — null until RE-2 resolves/creates it during qualification. */
    private UUID contactId;

    /** The buyer's qualification Deal — null until RE-2 materializes it. */
    private UUID dealId;

    /** The buyer's phone in E.164 (the {@code From} number) — the per-listing correlation key. */
    private String buyerPhone;

    @Builder.Default
    private ConversationState state = ConversationState.ASKING;

    /** The buyer↔concierge turns, oldest first. Assistant turns carry their grounding citations. */
    @Builder.Default
    private List<ConciergeTurn> turns = new ArrayList<>();

    /**
     * The buyer's accumulated qualification (RE-2) — budget / timeline / financing / intent extracted
     * across the conversation. Null until RE-2 runs the first qualification extraction; fields accumulate
     * turn-to-turn. Feeds the materialized {@link #dealId} Deal the nightly scorer tiers.
     */
    private BuyerQualification qualification;

    /** When the most recent inbound buyer text was received — drives the recency fallback + TTL window. */
    private Instant lastInboundAt;

    /** True once the buyer texts STOP (TCPA). */
    @Builder.Default
    private boolean optedOut = false;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
