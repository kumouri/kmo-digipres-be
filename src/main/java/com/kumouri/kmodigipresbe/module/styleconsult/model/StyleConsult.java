package com.kumouri.kmodigipresbe.module.styleconsult.model;

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
import java.util.List;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — a prospect's submitted style consult: the office-inbox row a salon
 * coordinator works. Created by {@code StyleConsultService} on a public widget/QR submission — the read
 * style {@link StyleAttributes} (typed or read off an inspiration photo by {@code AiVisionService}), the
 * recommended {@link ServiceRecommendation}s, the <strong>margin-aware</strong>
 * {@link RetailRecommendation}s, and a lifecycle {@link StyleConsultStatus}. Accepting it books a real
 * salon {@code Booking} (S3) and stamps {@link #bookingId}.
 *
 * <p>The salon-flavored twin of the T8 {@code QuoteRequest}: same office-inbox / nullable-triage-field
 * shape. {@code tenant_status_created_idx {tenantId, status, createdAt desc}} backs the office inbox
 * list as a pure DB read (newest first within a status filter); {@code tenant_contact_idx} backs the
 * per-contact lookup. {@code TenantScoped} + {@code Auditable} (a coordinator-facing CRM record). Not a
 * {@code CustomFieldHost}.
 *
 * <p>Every field is nullable/tolerant — a submission whose photo read nothing legible still records a
 * row (a generic consult) and is never dropped: AI is triage, not truth; a stylist confirms.
 */
@Document("style_consults")
@CompoundIndex(name = "tenant_status_created_idx",
        def = "{ 'tenantId': 1, 'status': 1, 'createdAt': -1 }")
@CompoundIndex(name = "tenant_contact_idx", def = "{ 'tenantId': 1, 'contactId': 1 }")
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class StyleConsult implements TenantScoped, Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The prospect Contact (found-or-created on intake from the phone/email they supplied; nullable). */
    private UUID contactId;

    /** The prospect's contact phone (E.164 where known) — the booking-link SMS target. */
    private String contactPhone;

    /** The prospect's contact email (where supplied). */
    private String contactEmail;

    /** A free-text note the prospect typed (e.g. "going to a wedding, want something low-maintenance"). */
    private String notes;

    /** The style attributes the consult was composed from (typed or vision-read). */
    private StyleAttributes attributes;

    /** The recommended salon services (from the tenant's ServiceMenu, by style→service rules). */
    @Builder.Default
    private List<ServiceRecommendation> serviceRecommendations = List.of();

    /** The recommended retail products — ranked margin-aware (highest margin first). */
    @Builder.Default
    private List<RetailRecommendation> retailRecommendations = List.of();

    /** The stored inspiration photo (when one was uploaded); the {@code Attachment} id. Nullable. */
    private UUID photoAttachmentId;

    @Builder.Default
    private StyleConsultStatus status = StyleConsultStatus.NEW;

    /** The salon {@code Booking} created on accept (S3); set once, the idempotency breadcrumb. Nullable. */
    private UUID bookingId;

    /** Set when the prospect accepts + the booking is created (idempotency breadcrumb). */
    private Instant bookedAt;

    /** The booking link that was texted on accept (per-tenant config snapshot; nullable). */
    private String bookingLinkSent;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    @Override
    public String getAuditEntityType() {
        return "STYLE_CONSULT";
    }
}
