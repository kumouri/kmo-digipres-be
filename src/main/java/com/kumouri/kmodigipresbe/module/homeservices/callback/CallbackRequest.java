package com.kumouri.kmodigipresbe.module.homeservices.callback;

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
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * T5 (Home Services "Instant Callback") — one materialized callback the dispatcher works: a customer who
 * hit voicemail and replied opting in for a callback. Recorded by {@code CallbackIntentHandler} when the
 * caller's reply is routed through the E2 responder.
 *
 * <h2>The revenue-ranked dispatcher queue (the headline)</h2>
 * {@link #revenueScore} is a <strong>deterministic</strong> rank score (no ML — {@code CallbackRevenueRanker})
 * stamped at create from the job-value band + urgency + recency the home-services voicemail intake already
 * produced (the DRAFT {@code WorkOrder}'s {@code customFields.jobValueBand}/{@code urgency}). The compound
 * index {@code tenant_status_score_idx {tenantId, status, revenueScore desc}} backs the
 * {@code GET /home-services/callbacks} ranked read as a pure DB sort.
 *
 * <p>{@link #callSid} (nullable) correlates the originating voicemail (for idempotent dedupe + the funnel);
 * {@link #workOrderId} (nullable) is the DRAFT WorkOrder the multi-trade intake created (the revenue
 * signal source). {@link #summaryLine} is the AI one-liner copied from the voicemail {@code Activity}
 * summary (best-effort, nullable). Every revenue/triage field is nullable — a reply with no prior
 * voicemail still records a card and is never dropped (AI is triage, not truth).
 *
 * <p>{@code TenantScoped} for isolation + {@code Auditable} (a dispatcher-facing CRM record).
 */
@Document("callback_requests")
@CompoundIndex(name = "tenant_status_score_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'revenueScore': -1 }")
@CompoundIndex(name = "tenant_callsid_idx", def = "{ 'tenantId': 1, 'callSid': 1 }", sparse = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CallbackRequest implements TenantScoped, Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The caller's Contact (found-or-created by the voicemail intake; correlated by phone here). */
    private UUID contactId;

    /** The caller's phone (E.164, the inbound {@code From}). */
    private String fromPhone;

    /** The originating voicemail's Twilio CallSid (nullable — a reply with no prior voicemail). */
    private String callSid;

    /**
     * The DRAFT {@code WorkOrder} the multi-trade voicemail intake created for this caller (nullable —
     * mole/NMM or no-WO verticals, or a reply with no prior voicemail). The revenue-signal source.
     */
    private UUID workOrderId;

    /** Immediate vs scheduled — from the E2-classified intent. */
    private CallbackMode mode;

    /** The caller's raw window phrase, e.g. "in 30 min" / "after 5pm" (always kept; nullable for NOW). */
    private String requestedWindowText;

    /** The best-effort parsed window instant (nullable — unparseable phrases keep only the text). */
    private Instant requestedAt;

    @Builder.Default
    private CallbackStatus status = CallbackStatus.REQUESTED;

    /** The AI one-line summary copied from the voicemail Activity (nullable, best-effort). */
    private String summaryLine;

    /** The routing urgency copied from the WorkOrder {@code customFields.urgency} (nullable). */
    private String urgency;

    /** The coarse $-band copied from the WorkOrder {@code customFields.jobValueBand} (nullable). */
    private String jobValueBand;

    /** The deterministic rank score (higher = work first). Stamped at create; re-derivable. */
    @Builder.Default
    private long revenueScore = 0L;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
