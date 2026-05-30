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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit coverage of {@link ActivitySubjectResolver}: per-type name resolution,
 * the WorkOrder title/number fallback, blank/missing &rarr; empty, and the
 * gated-module case where the {@link WorkOrderRepository} bean is absent.
 */
class ActivitySubjectResolverTest {

    private final ContactRepository contacts = mock(ContactRepository.class);
    private final CompanyRepository companies = mock(CompanyRepository.class);
    private final DealRepository deals = mock(DealRepository.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<WorkOrderRepository> woProvider = mock(ObjectProvider.class);
    private final WorkOrderRepository workOrders = mock(WorkOrderRepository.class);

    private ActivitySubjectResolver resolver() {
        return new ActivitySubjectResolver(contacts, companies, deals, woProvider);
    }

    @Test
    void resolvesContactDisplayName() {
        UUID id = UUID.randomUUID();
        when(contacts.findById(id)).thenReturn(Mono.just(
                Contact.builder().id(id).displayName("Ada Lovelace").build()));
        StepVerifier.create(resolver().resolveName(SubjectType.CONTACT, id))
                .expectNext("Ada Lovelace")
                .verifyComplete();
    }

    @Test
    void resolvesCompanyName() {
        UUID id = UUID.randomUUID();
        when(companies.findById(id)).thenReturn(Mono.just(
                Company.builder().id(id).name("Analytical Engines Ltd").build()));
        StepVerifier.create(resolver().resolveName(SubjectType.COMPANY, id))
                .expectNext("Analytical Engines Ltd")
                .verifyComplete();
    }

    @Test
    void resolvesDealTitle() {
        UUID id = UUID.randomUUID();
        when(deals.findById(id)).thenReturn(Mono.just(
                Deal.builder().id(id).title("Website rebuild").build()));
        StepVerifier.create(resolver().resolveName(SubjectType.DEAL, id))
                .expectNext("Website rebuild")
                .verifyComplete();
    }

    @Test
    void workOrderPrefersTitleOverNumber() {
        UUID id = UUID.randomUUID();
        when(woProvider.getIfAvailable()).thenReturn(workOrders);
        when(workOrders.findById(id)).thenReturn(Mono.just(
                WorkOrder.builder().id(id).title("Spring inspection").workOrderNumber("2026-05-0001").build()));
        StepVerifier.create(resolver().resolveName(SubjectType.WORK_ORDER, id))
                .expectNext("Spring inspection")
                .verifyComplete();
    }

    @Test
    void workOrderFallsBackToNumberWhenTitleBlank() {
        UUID id = UUID.randomUUID();
        when(woProvider.getIfAvailable()).thenReturn(workOrders);
        when(workOrders.findById(id)).thenReturn(Mono.just(
                WorkOrder.builder().id(id).title("   ").workOrderNumber("2026-05-0002").build()));
        StepVerifier.create(resolver().resolveName(SubjectType.WORK_ORDER, id))
                .expectNext("2026-05-0002")
                .verifyComplete();
    }

    @Test
    void workOrderEmptyWhenModuleDisabled() {
        UUID id = UUID.randomUUID();
        when(woProvider.getIfAvailable()).thenReturn(null);
        StepVerifier.create(resolver().resolveName(SubjectType.WORK_ORDER, id))
                .verifyComplete(); // empty
    }

    @Test
    void blankNameResolvesToEmpty() {
        UUID id = UUID.randomUUID();
        when(contacts.findById(id)).thenReturn(Mono.just(
                Contact.builder().id(id).displayName("  ").build()));
        StepVerifier.create(resolver().resolveName(SubjectType.CONTACT, id))
                .verifyComplete(); // empty
    }

    @Test
    void missingEntityResolvesToEmpty() {
        UUID id = UUID.randomUUID();
        when(deals.findById(id)).thenReturn(Mono.empty());
        StepVerifier.create(resolver().resolveName(SubjectType.DEAL, id))
                .verifyComplete(); // empty
    }

    @Test
    void nullTypeOrIdResolvesToEmpty() {
        StepVerifier.create(resolver().resolveName(null, UUID.randomUUID())).verifyComplete();
        StepVerifier.create(resolver().resolveName(SubjectType.CONTACT, null)).verifyComplete();
    }
}
