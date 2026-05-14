package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.util.UUID;

/**
 * Picks the right {@link InvoiceRepository} method based on
 * {@code (contact.companyId != null, status != null)}. Branching the dispatch here keeps
 * the controller a pure handler and avoids the {@code {companyId: null}} predicate trap
 * in the {@code $or}-based queries — when a contact has no companyId we use the
 * contactId-only derived methods instead.
 */
@Service
@RequiredArgsConstructor
public class PortalInvoicesService {

    private final InvoiceRepository invoices;

    public Flux<Invoice> listForContact(Contact contact, Invoice.Status status) {
        UUID tenantId = contact.getTenantId();
        UUID contactId = contact.getId();
        UUID companyId = contact.getCompanyId();
        if (companyId != null) {
            return status == null
                    ? invoices.findAllByTenantAndContactOrCompany(tenantId, contactId, companyId)
                    : invoices.findAllByTenantAndContactOrCompanyAndStatus(
                            tenantId, contactId, companyId, status);
        }
        return status == null
                ? invoices.findAllByTenantIdAndContactIdOrderByIssuedAtDesc(tenantId, contactId)
                : invoices.findAllByTenantIdAndContactIdAndStatusOrderByIssuedAtDesc(
                        tenantId, contactId, status);
    }
}
