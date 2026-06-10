package com.kumouri.kmodigipresbe.module.quoting.closer;

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
 * T11 (Home "QuoteCloser") — the per-tenant configuration for the un-accepted-quote nurture + won-job
 * review composition. One row per tenant (unique {@code tenant_idx {tenantId}}); a tenant-scoped CRM
 * entity ({@code Auditable}). The {@code MidnightResponderConfig} (T3) precedent.
 *
 * <h2>What it configures (never hardcoded)</h2>
 * <ul>
 *   <li><strong>{@link #campaignId}</strong> — the {@code NurtureCampaign} (E1) a {@code NEW}
 *       (un-accepted) {@code QuoteRequest} (T8) is auto-enrolled into once it has sat unaccepted past the
 *       window. A null id ⇒ the QuoteCloser enrollment job is a clean no-op for this tenant (it enrolls
 *       nowhere). The campaign should be tagged {@code vertical="home"} (home has NO registered nurture
 *       copy filter ⇒ the cadence copy is sent unfiltered — the correct GATE-2 third-vertical behavior).
 *       Validated at save-time against the tenant's campaigns ({@code 4471}).</li>
 *   <li><strong>{@link #unacceptedWindowHours}</strong> — how long a {@code NEW} quote may sit unaccepted
 *       before the QuoteCloser enrollment job enrolls its contact in the cadence (the reminder →
 *       financing-nudge → last-call sequence). Default {@link #DEFAULT_UNACCEPTED_WINDOW_HOURS}.</li>
 * </ul>
 *
 * <h2>Blast radius zero</h2>
 * A tenant with no {@code QuoteCloserConfig} row (or a null {@code campaignId}) behaves exactly as before
 * T11: the enrollment job finds no campaign mapping ⇒ no enroll; the {@code QUOTE_ACCEPTED} subscriber
 * still creates the won-job review request (it needs no config — the review leg is config-independent).
 * The doc only ever ADDS the un-accepted-quote nurture on top of the shipped quoting + nurture; it never
 * changes a quote synthesis / accept outcome.
 */
@Document("quote_closer_config")
@CompoundIndex(name = "tenant_idx", def = "{ 'tenantId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class QuoteCloserConfig implements TenantScoped, Auditable {

    /** The default window a {@code NEW} quote may sit unaccepted before the cadence enrolls it (hours). */
    public static final int DEFAULT_UNACCEPTED_WINDOW_HOURS = 24;

    @Id
    private UUID id;

    private UUID tenantId;

    /**
     * The {@code NurtureCampaign} a {@code NEW} (un-accepted) quote's contact auto-enrolls into; null ⇒
     * the QuoteCloser enrollment job routes nowhere for this tenant (a clean no-op). Validated at
     * save-time against the tenant's campaigns ({@code 4471}).
     */
    private UUID campaignId;

    /** How long a {@code NEW} quote may sit unaccepted before the cadence enrolls it (hours). */
    @Builder.Default
    private int unacceptedWindowHours = DEFAULT_UNACCEPTED_WINDOW_HOURS;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
