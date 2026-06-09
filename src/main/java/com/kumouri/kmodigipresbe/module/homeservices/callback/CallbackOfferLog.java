package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.tenancy.TenantScoped;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.UUID;

/**
 * T5 (Home Services "Instant Callback") — the per-CallSid <strong>offer-sent dedupe ledger</strong>: one
 * row per voicemail for which the callback opt-in SMS was sent. Written <strong>ledger-insert-FIRST</strong>
 * by {@code CallbackOfferSubscriber} BEFORE the caller SMS, with a unique
 * {@code tenant_callsid_idx {tenantId, callSid}} — a concurrent / re-fired {@code VOICEMAIL_LEAD_CREATED}
 * for the same CallSid hits the unique index → {@code DuplicateKeyException} → {@code Mono.empty()} = zero
 * duplicate offer. The money-grade {@code CoverageNudgeLog} / {@code RecurringInvoiceOccurrence} idempotency
 * pattern, here for a non-money SMS. <strong>Never {@code switchIfEmpty(send)}.</strong>
 *
 * <p>{@code TenantScoped} for isolation; <strong>not {@code Auditable}</strong> (a system ledger — the
 * {@code CoverageNudgeLog} rationale).
 */
@Document("callback_offer_logs")
@CompoundIndex(name = "tenant_callsid_idx", def = "{ 'tenantId': 1, 'callSid': 1 }", unique = true)
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class CallbackOfferLog implements TenantScoped {

    @Id
    private UUID id;

    private UUID tenantId;

    /** The voicemail's Twilio CallSid — the per-offer dedupe key. */
    private String callSid;

    /** The caller Contact the offer was sent to. */
    private UUID contactId;

    /** When the offer SMS was sent. */
    private Instant offeredAt;
}
