package com.kumouri.kmodigipresbe.model.response;

import com.kumouri.kmodigipresbe.service.billing.StripeCheckoutService;

/**
 * Portal-facing Stripe checkout response (Phase G — G.3/G.4).
 *
 * <p>Exposes only the {@code checkoutUrl} returned by
 * {@link StripeCheckoutService#createCheckoutForInvoice} — drops the internal
 * {@code mode} and {@code invoiceId} echo fields from
 * {@link StripeCheckoutService.CheckoutResult} (G-D1: portal projection records
 * never leak staff-internal fields).
 *
 * <p>Used by the portal pay endpoint (G.4). Defined here in G.3 (per the plan §4
 * G.3 file list); no caller exists until G.4 — an unused record with no side effects
 * is not dead behaviour.
 */
public record PortalCheckoutResponse(String checkoutUrl) {

    public static PortalCheckoutResponse from(StripeCheckoutService.CheckoutResult result) {
        return new PortalCheckoutResponse(result.url());
    }
}
