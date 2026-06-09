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
 * T5 (Home Services "Instant Callback") — the per-tenant <strong>callback copy book</strong>: the opt-in
 * offer SMS the {@code CallbackOfferSubscriber} sends and the confirmation copy the
 * {@code CallbackIntentHandler} returns. One row per tenant (unique {@code tenant_idx}); a tenant-scoped
 * CRM entity ({@code TenantScoped} + {@code Auditable}). The {@code SwitchboardConfig} precedent —
 * <strong>business copy is never hardcoded</strong>; each field has a sensible generic default when blank.
 */
@Document("callback_configs")
@CompoundIndex(name = "tenant_idx", def = "{ 'tenantId': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CallbackConfig implements TenantScoped, Auditable {

    @Id
    private UUID id;

    private UUID tenantId;

    /**
     * The opt-in SMS texted to a caller after a home-services voicemail. Blank ⇒ the subscriber uses
     * {@link CallbackCopy#DEFAULT_OFFER_MESSAGE}.
     */
    private String offerMessage;

    /**
     * The confirmation reply when the caller asks for an immediate callback. Blank ⇒ the handler uses
     * {@link CallbackCopy#DEFAULT_IMMEDIATE_CONFIRM}.
     */
    private String immediateConfirmMessage;

    /**
     * The confirmation reply when the caller names a time/window. Blank ⇒ the handler uses
     * {@link CallbackCopy#DEFAULT_SCHEDULED_CONFIRM}.
     */
    private String scheduledConfirmMessage;

    @Version
    private Long version;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
