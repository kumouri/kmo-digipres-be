package com.kumouri.kmodigipresbe.service.portal;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contract.Contract;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.quote.Quote;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.repository.QuoteRepository;
import com.kumouri.kmodigipresbe.repository.contract.ContractRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Access-control guard for single-entity portal operations (Phase G — G-D2).
 *
 * <p>Every method follows the same pattern:
 * <ol>
 *   <li>Resolve the caller's {@link Contact} via
 *       {@link PortalLinkedContactResolver#resolve()} (tenant from JWT, contact
 *       from {@code User.contactId}, tenant-scoped contact load — the single
 *       access-control chokepoint for all {@code /portal/me/**} endpoints).</li>
 *   <li>Load the entity by a <strong>tenant-scoped</strong>
 *       {@code findByTenantIdAndId(contact.getTenantId(), id)} — cross-tenant
 *       leakage is blocked at the query level.</li>
 *   <li>When the entity is absent: surface {@code 38xx + 404}.</li>
 *   <li>When the entity exists but is NOT owned by the caller's contact/company:
 *       surface the <strong>same</strong> {@code 38xx + 404} — deliberately
 *       identical to the not-found error (the no-enumeration-oracle confidentiality
 *       choice, G-D2). A portal user must NOT be able to distinguish "exists but
 *       isn't yours" from "no such entity".</li>
 * </ol>
 *
 * <p>{@code isOwnedBy} is a pure static predicate — never matches {@code null==null},
 * so a null {@code contactId}/{@code companyId} on the entity does NOT accidentally
 * match.
 *
 * <p>Lifecycle note: {@code PortalOwnershipGuard} is a <strong>stateless</strong>
 * {@code @Component} — it holds no SDK client, no HTTP/Netty client, no pool,
 * no scheduler, and no thread pool. Verified: no new resource-owning bean is
 * introduced by this class (the Phase-F F.2/F.11 lifecycle checklist — mandatory
 * regardless; answered "none" explicitly here).
 */
@Component
@RequiredArgsConstructor
public class PortalOwnershipGuard {

    private final PortalLinkedContactResolver linkedContact;
    private final InvoiceRepository invoices;
    private final QuoteRepository quotes;
    private final ProjectRepository projects;
    private final ContractRepository contracts;

    // -------------------------------------------------------------------------
    // Public guard methods
    // -------------------------------------------------------------------------

    /**
     * Resolves the {@link Invoice} identified by {@code id} and confirms it belongs
     * to the caller's contact or company. Both not-found and not-owned return the
     * same {@code 3801 / 404} (no enumeration oracle).
     */
    public Mono<Invoice> requireOwnedInvoice(UUID id) {
        return linkedContact.resolve().flatMap(contact ->
                invoices.findByTenantIdAndId(contact.getTenantId(), id)
                        .switchIfEmpty(Mono.error(() ->
                                new DigiPresBeException(
                                        "Invoice not found", 3801, 404)))
                        .flatMap(invoice -> invoiceOwnedBy(invoice, contact)
                                ? Mono.just(invoice)
                                : Mono.error(() ->
                                        new DigiPresBeException(
                                                "Invoice not found", 3801, 404))));
    }

    /**
     * Resolves the {@link Quote} identified by {@code id} and confirms it belongs
     * to the caller's contact or company. Both not-found and not-owned return the
     * same {@code 3802 / 404} (no enumeration oracle).
     */
    public Mono<Quote> requireOwnedQuote(UUID id) {
        return linkedContact.resolve().flatMap(contact ->
                quotes.findByTenantIdAndId(contact.getTenantId(), id)
                        .switchIfEmpty(Mono.error(() ->
                                new DigiPresBeException(
                                        "Quote not found", 3802, 404)))
                        .flatMap(quote -> quoteOwnedBy(quote, contact)
                                ? Mono.just(quote)
                                : Mono.error(() ->
                                        new DigiPresBeException(
                                                "Quote not found", 3802, 404))));
    }

    /**
     * Resolves the {@link Project} identified by {@code id} and confirms it belongs
     * to the caller's contact or company. Both not-found and not-owned return the
     * same {@code 3803 / 404} (no enumeration oracle).
     */
    public Mono<Project> requireOwnedProject(UUID id) {
        return linkedContact.resolve().flatMap(contact ->
                projects.findByTenantIdAndId(contact.getTenantId(), id)
                        .switchIfEmpty(Mono.error(() ->
                                new DigiPresBeException(
                                        "Project not found", 3803, 404)))
                        .flatMap(project -> projectOwnedBy(project, contact)
                                ? Mono.just(project)
                                : Mono.error(() ->
                                        new DigiPresBeException(
                                                "Project not found", 3803, 404))));
    }

    /**
     * Resolves the {@link Contract} identified by {@code id} and confirms it belongs
     * to the caller's contact or company. Both not-found and not-owned return the
     * same {@code 3804 / 404} (no enumeration oracle).
     */
    public Mono<Contract> requireOwnedContract(UUID id) {
        return linkedContact.resolve().flatMap(contact ->
                contracts.findByTenantIdAndId(contact.getTenantId(), id)
                        .switchIfEmpty(Mono.error(() ->
                                new DigiPresBeException(
                                        "Contract not found", 3804, 404)))
                        .flatMap(contract -> contractOwnedBy(contract, contact)
                                ? Mono.just(contract)
                                : Mono.error(() ->
                                        new DigiPresBeException(
                                                "Contract not found", 3804, 404))));
    }

    // -------------------------------------------------------------------------
    // Private ownership predicates — never match null == null
    // -------------------------------------------------------------------------

    private static boolean invoiceOwnedBy(Invoice invoice, Contact contact) {
        return (invoice.getContactId() != null
                && invoice.getContactId().equals(contact.getId()))
                || (contact.getCompanyId() != null
                && invoice.getCompanyId() != null
                && invoice.getCompanyId().equals(contact.getCompanyId()));
    }

    private static boolean quoteOwnedBy(Quote quote, Contact contact) {
        return (quote.getContactId() != null
                && quote.getContactId().equals(contact.getId()))
                || (contact.getCompanyId() != null
                && quote.getCompanyId() != null
                && quote.getCompanyId().equals(contact.getCompanyId()));
    }

    private static boolean projectOwnedBy(Project project, Contact contact) {
        return (project.getPrimaryContactId() != null
                && project.getPrimaryContactId().equals(contact.getId()))
                || (contact.getCompanyId() != null
                && project.getCompanyId() != null
                && project.getCompanyId().equals(contact.getCompanyId()));
    }

    private static boolean contractOwnedBy(Contract contract, Contact contact) {
        return (contract.getContactId() != null
                && contract.getContactId().equals(contact.getId()))
                || (contact.getCompanyId() != null
                && contract.getCompanyId() != null
                && contract.getCompanyId().equals(contact.getCompanyId()));
    }
}
