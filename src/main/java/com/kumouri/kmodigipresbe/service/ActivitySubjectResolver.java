package com.kumouri.kmodigipresbe.service;

import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Company;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.CompanyRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Resolves an activity subject ({@code subjectType} + {@code subjectId}) to a
 * human-readable display name, so the FE can render "Contact / Ada Lovelace"
 * instead of "Contact / &lt;uuid&gt;". Read-only enrichment — never mutates.
 *
 * <p>Per-type name field: {@code CONTACT}&rarr;{@code displayName},
 * {@code COMPANY}&rarr;{@code name}, {@code DEAL}&rarr;{@code title},
 * {@code WORK_ORDER}&rarr;{@code title} when set, else {@code workOrderNumber}.
 *
 * <p>All look-ups go through the tenant-scoped repositories
 * ({@link com.kumouri.kmodigipresbe.tenancy.TenantScopedSimpleReactiveMongoRepository}
 * auto-filters {@code findById} by the request's tenant), so a subject in another
 * tenant never resolves. The field-service {@link WorkOrderRepository} bean is
 * optional (the module may be gated off) — injected via {@link ObjectProvider}
 * so {@code WORK_ORDER} simply resolves to empty when the module is disabled.
 * A missing/blank name resolves to {@link Mono#empty()} (the caller then falls
 * back to the raw id).
 */
@Component
public class ActivitySubjectResolver {

    private final ContactRepository contacts;
    private final CompanyRepository companies;
    private final DealRepository deals;
    private final ObjectProvider<WorkOrderRepository> workOrders;

    public ActivitySubjectResolver(ContactRepository contacts,
                                   CompanyRepository companies,
                                   DealRepository deals,
                                   ObjectProvider<WorkOrderRepository> workOrders) {
        this.contacts = contacts;
        this.companies = companies;
        this.deals = deals;
        this.workOrders = workOrders;
    }

    /** Resolve the subject's display name, or {@link Mono#empty()} if unknown/blank. */
    public Mono<String> resolveName(SubjectType type, UUID subjectId) {
        if (type == null || subjectId == null) {
            return Mono.empty();
        }
        Mono<String> name = switch (type) {
            case CONTACT -> contacts.findById(subjectId).mapNotNull(Contact::getDisplayName);
            case COMPANY -> companies.findById(subjectId).mapNotNull(Company::getName);
            case DEAL -> deals.findById(subjectId).mapNotNull(Deal::getTitle);
            case WORK_ORDER -> resolveWorkOrder(subjectId);
        };
        return name.filter(s -> !s.isBlank());
    }

    private Mono<String> resolveWorkOrder(UUID subjectId) {
        WorkOrderRepository repo = workOrders.getIfAvailable();
        if (repo == null) {
            return Mono.empty();
        }
        return repo.findById(subjectId).mapNotNull(ActivitySubjectResolver::workOrderLabel);
    }

    /** Prefer the title; fall back to the work-order number; null if neither is set. */
    private static String workOrderLabel(WorkOrder wo) {
        String title = wo.getTitle();
        if (title != null && !title.isBlank()) {
            return title;
        }
        return wo.getWorkOrderNumber();
    }
}
