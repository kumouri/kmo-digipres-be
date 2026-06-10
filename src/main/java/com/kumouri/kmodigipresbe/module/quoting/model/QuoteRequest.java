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
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — a homeowner's submitted instant quote: the office-inbox row a
 * dispatcher works. Created by {@code QuoteIntakeService} on a public widget/QR submission —
 * attributes (typed or read from a photo), the synthesized price {@link QuoteRange}, the
 * {@link RepairVsReplace} recommendation, and a lifecycle {@link QuoteStatus}.
 *
 * <p>{@code tenant_status_created_idx {tenantId, status, createdAt desc}} backs the office inbox
 * list as a pure DB read (newest first within a status filter). {@code TenantScoped} + {@code
 * Auditable} (a dispatcher-facing CRM record). Not a {@code CustomFieldHost}.
 *
 * <p>Every triage field is nullable — a submission with a photo that read nothing legible still
 * records a row (a diagnostic-visit quote) and is never dropped: AI is triage, not truth.
 */
@Document("quote_requests")
@CompoundIndex(name = "tenant_status_created_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'createdAt': -1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class QuoteRequest implements TenantScoped, Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The homeowner Contact (found-or-created on intake from the phone/email they supplied; nullable). */
    private UUID contactId;

    /** The homeowner's contact phone (E.164 where known) — the booking-link SMS target. */
    private String contactPhone;

    /** The homeowner's contact email (where supplied). */
    private String contactEmail;

    /** A free-text problem description the homeowner typed (nullable). */
    private String problemDescription;

    /** The attributes the quote was synthesized from (typed or vision-read). */
    private QuoteAttributes attributes;

    /** The synthesized price RANGE — the product. Always carries the estimate disclaimer. */
    private QuoteRange range;

    /** The explained repair-vs-replace recommendation (+ financing flag on REPLACE). */
    private RepairVsReplace repairVsReplace;

    /** The stored equipment photo (when one was uploaded); the {@code Attachment} id. Nullable. */
    private UUID photoAttachmentId;

    @Builder.Default
    private QuoteStatus status = QuoteStatus.NEW;

    /** Set when the homeowner accepts + the booking link is texted (idempotency breadcrumb). */
    private Instant acceptedAt;

    /** The booking link that was sent on accept (per-tenant config snapshot; nullable). */
    private String bookingLinkSent;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
