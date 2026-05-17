package com.kumouri.kmodigipresbe.controller;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.service.billing.AccountingPushService;
import com.kumouri.kmodigipresbe.service.billing.StripeCheckoutService;
import com.kumouri.kmodigipresbe.service.billing.StripeCheckoutService.CheckoutResult;
import com.kumouri.kmodigipresbe.service.billing.StripeCheckoutService.Mode;
import com.kumouri.kmodigipresbe.tenancy.RoleGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Phase E (E-D9 / E-D10) — the two module-gated billing endpoints that hang off
 * the {@code /invoices/{id}} path.
 *
 * <p>These are split into their own {@code @ConditionalOnProperty(kmosf.modules.
 * billing-stripe, matchIfMissing=true)}-gated {@code @RestController} (rather than
 * added to {@code InvoiceController}) precisely because E-D9 requires the
 * Stripe-checkout endpoint to be module-gated while the core
 * {@code POST /invoices} / {@code /payments} (extended with {@code @IdempotentRoute}
 * in place on {@code InvoiceController}) must stay always-on. WebFlux composes
 * multiple {@code @RestController}s on the same base path with no conflict; this
 * is the same {@code @ConditionalOnProperty}-per-controller pattern used by the
 * Phase-C/D feature controllers. (Documented as a minor reconciliation of E-D9's
 * gate requirement with E-D12's "extended in place" — see the PR description.)
 *
 * <ul>
 *   <li>{@code POST /invoices/{id}/stripe-checkout?mode=} — {@code @IdempotentRoute}
 *       (E-D6/E-D9; it creates a real billable checkout link). WireMock/sandbox
 *       only — no live Stripe (§7).</li>
 *   <li>{@code POST /invoices/{id}/accounting-push} — ADMIN-only
 *       ({@code RoleGuard.requireRole("ADMIN")}); reaches the shipped
 *       {@code INVOICE_FINALIZED → QuickBooksInvoiceSync} seam (E-D10), naturally
 *       idempotent via {@code externalRefs["quickbooks"]}.</li>
 * </ul>
 */
@RestController
@RequestMapping("/invoices")
@ConditionalOnProperty(prefix = "kmosf.modules.billing-stripe", name = "enabled", matchIfMissing = true)
@RequiredArgsConstructor
public class InvoiceBillingStripeController {

    private final StripeCheckoutService stripeCheckout;
    private final AccountingPushService accountingPush;

    /**
     * Generates a Stripe Checkout Session / Payment Link URL carrying
     * {@code metadata.kmosf_invoice_id}. {@code @IdempotentRoute} (a retry with the
     * same key replays the original URL response rather than creating a second
     * Stripe session). WireMock/sandbox only — never live Stripe (§7).
     */
    @PostMapping("/{id}/stripe-checkout")
    @IdempotentRoute
    public Mono<CheckoutResult> stripeCheckout(@PathVariable UUID id,
                                                @RequestParam(required = false) Mode mode) {
        return stripeCheckout.createCheckoutForInvoice(id, mode);
    }

    /**
     * Debug accounting-push round-trip (E-D10). ADMIN-only. DRAFT→SENT (fires
     * {@code INVOICE_FINALIZED} → the shipped QuickBooksInvoiceSync) or, if already
     * SENT, re-publishes {@code INVOICE_FINALIZED} to force a re-sync. Naturally
     * idempotent end-to-end via {@code externalRefs["quickbooks"]}.
     */
    @PostMapping("/{id}/accounting-push")
    public Mono<Invoice> accountingPush(@PathVariable UUID id) {
        return RoleGuard.requireRole("ADMIN").then(accountingPush.push(id));
    }
}
