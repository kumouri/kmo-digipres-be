package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.model.response.PortalCheckoutResponse;
import com.kumouri.kmodigipresbe.model.response.PortalInvoiceSummary;
import com.kumouri.kmodigipresbe.service.billing.StripeCheckoutService;
import com.kumouri.kmodigipresbe.service.portal.PortalInvoiceViewService;
import com.kumouri.kmodigipresbe.service.portal.PortalInvoicesService;
import com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver;
import com.kumouri.kmodigipresbe.service.portal.PortalOwnershipGuard;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.util.UUID;

/**
 * Portal invoices surface — list (pre-existing) + detail + pay (Phase G — G.4).
 *
 * <p>Read-only portal invoices listing. Caller sees invoices linked to their Contact
 * either directly (Invoice.contactId == ctx.contactId) or via the Contact's company
 * (Invoice.companyId == ctx.contact.companyId). Cross-tenant isolation is enforced
 * via the resolver and the explicit tenantId predicate on every repository query —
 * the {@code TenantScopedReactiveMongoRepository} marker does not auto-scope.
 *
 * <h2>G.4 additions</h2>
 * <ul>
 *   <li>{@code GET /invoices/{id}} — ownership-gated detail + best-effort
 *       {@code INVOICE_VIEWED_BY_CLIENT} advisory event + {@code Activity(NOTE)} row
 *       (G-D4). The activity/event emission MUST NOT fail the read.</li>
 *   <li>{@code POST /invoices/{id}/pay} — {@code @IdempotentRoute}; ownership gate
 *       TEXTUALLY AND REACTIVELY PRECEDES the {@link StripeCheckoutService} call
 *       (§9 #2, the most security-critical ordering). A non-owned invoice never
 *       reaches Stripe ({@code 3801 / 404} at the gate). WireMock/sandbox only —
 *       never live Stripe (§7 hard line).</li>
 * </ul>
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalInvoicesController {

    private final PortalLinkedContactResolver linkedContact;
    private final PortalInvoicesService service;
    private final PortalOwnershipGuard ownershipGuard;
    private final PortalInvoiceViewService viewService;
    private final StripeCheckoutService stripeCheckout;

    @GetMapping("/invoices")
    public Flux<PortalInvoiceSummary> listInvoices(
            @RequestParam(value = "status", required = false) Invoice.Status status) {
        return linkedContact.resolve()
                .flatMapMany(contact -> service.listForContact(contact, status))
                .map(PortalInvoiceSummary::from);
    }

    /**
     * Returns a portal-safe invoice projection for a single owned invoice.
     *
     * <p>Best-effort side effects (G-D4): emits {@code INVOICE_VIEWED_BY_CLIENT} advisory
     * event and creates an {@code Activity(type=NOTE)} row via
     * {@link PortalInvoiceViewService}. Both are fire-and-forget — any failure is
     * swallowed with a WARN log so that a telemetry write never fails the read. The
     * {@code onErrorResume(e -> Mono.empty())} wrapping the view-service call is the
     * explicit best-effort contract.
     *
     * <p>The contact is resolved in parallel with the ownership-gated invoice to avoid
     * a second sequential resolver invocation.
     */
    @GetMapping("/invoices/{id}")
    public Mono<PortalInvoiceSummary> getInvoice(@PathVariable UUID id) {
        // Zip: resolve contact (for the view-activity) + gate-and-load invoice
        // concurrently. Both subscribe to the same Reactor context (same JWT tenant).
        return Mono.zip(
                        linkedContact.resolve(),
                        ownershipGuard.requireOwnedInvoice(id))
                .flatMap(tuple -> {
                    var contact = tuple.getT1();
                    var invoice = tuple.getT2();
                    // Best-effort side-effects: MUST NOT fail the read (G-D4).
                    return viewService.recordView(invoice, contact)
                            .onErrorResume(e -> Mono.empty())
                            .thenReturn(Tuples.of(contact, invoice));
                })
                .map(tuple -> PortalInvoiceSummary.from(tuple.getT2()));
    }

    /**
     * Generates a Stripe Checkout URL for a portal-owned invoice.
     *
     * <p><strong>§9 #2 GATE-BEFORE-CHECKOUT (most scrutinized):</strong> the ownership
     * gate ({@link PortalOwnershipGuard#requireOwnedInvoice}) TEXTUALLY AND REACTIVELY
     * PRECEDES the {@link StripeCheckoutService#createCheckoutForInvoice} call — the
     * Stripe call is inside the {@code .flatMap} AFTER the gate resolves. A non-owned
     * or cross-tenant invoice errors at {@code requireOwnedInvoice} with {@code 3801}
     * <em>before any Stripe traffic is generated</em> (proven by
     * {@code PortalInvoicePayWireMockIT}: rejected invoice → 3801 + WireMock
     * recorded ZERO requests).
     *
     * <p>{@code @IdempotentRoute}: a retry with the same {@code Idempotency-Key} header
     * replays the stored 2xx response without generating a second Stripe session.
     */
    @PostMapping("/invoices/{id}/pay")
    @IdempotentRoute
    public Mono<PortalCheckoutResponse> payInvoice(@PathVariable UUID id) {
        return ownershipGuard.requireOwnedInvoice(id)
                .flatMap(inv -> stripeCheckout.createCheckoutForInvoice(
                        inv.getId(), StripeCheckoutService.Mode.CHECKOUT_SESSION))
                .map(PortalCheckoutResponse::from);
    }
}
