package com.kumouri.kmodigipresbe.controller.portal;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.response.PortalInvoiceSummary;
import com.kumouri.kmodigipresbe.service.portal.PortalInvoicesService;
import com.kumouri.kmodigipresbe.service.portal.PortalLinkedContactResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

/**
 * Read-only portal invoices listing. Caller sees invoices linked to their Contact
 * either directly (Invoice.contactId == ctx.contactId) or via the Contact's company
 * (Invoice.companyId == ctx.contact.companyId). Cross-tenant isolation is enforced
 * via the resolver and the explicit tenantId predicate on every repository query —
 * the {@code TenantScopedReactiveMongoRepository} marker does not auto-scope.
 */
@RestController
@RequestMapping("/portal/me")
@RequiredArgsConstructor
public class PortalInvoicesController {

    private final PortalLinkedContactResolver linkedContact;
    private final PortalInvoicesService service;

    @GetMapping("/invoices")
    public Flux<PortalInvoiceSummary> listInvoices(
            @RequestParam(value = "status", required = false) Invoice.Status status) {
        return linkedContact.resolve()
                .flatMapMany(contact -> service.listForContact(contact, status))
                .map(PortalInvoiceSummary::from);
    }
}
