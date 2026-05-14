package com.kumouri.kmodigipresbe.module.fieldservice.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Phase 10e — unit coverage of the {@code WORK_ORDER_EN_ROUTE} event emission
 * in {@link WorkOrderService#update}. The transition rule is precise:
 * <ul>
 *   <li>Emit when previous != EN_ROUTE AND new == EN_ROUTE.</li>
 *   <li>Do not emit when status field is unchanged (e.g. patch updates only
 *       {@code notes}).</li>
 *   <li>Do not emit when previous was already EN_ROUTE (re-save of a WO
 *       already en route is a no-op for downstream automation).</li>
 *   <li>Do not emit when transitioning out of EN_ROUTE to a different status.</li>
 * </ul>
 *
 * <p>Phone-resolution path: when the JobSite's Contact has a {@code +}-prefixed
 * phone in its {@code phones} list, the event payload carries it as
 * {@code contactPhoneE164}; when not, the field is {@code null} but the event
 * still fires (the dispatcher's SEND_SMS branch handles the null skip).
 */
class WorkOrderEnRouteEventTest {

    private WorkOrderRepository workOrders;
    private JobSiteRepository jobSites;
    private ContactRepository contacts;
    private DomainEventPublisher events;
    private WorkOrderService service;

    @BeforeEach
    void setup() {
        workOrders = mock(WorkOrderRepository.class);
        jobSites = mock(JobSiteRepository.class);
        contacts = mock(ContactRepository.class);
        events = mock(DomainEventPublisher.class);
        ActivityRepository activities = mock(ActivityRepository.class);
        RecurrenceExpansionService recurrence = mock(RecurrenceExpansionService.class);
        service = new WorkOrderService(
                workOrders, activities, recurrence, jobSites, contacts, events);
    }

    @Test
    void transitionToEnRoute_publishesEventWithResolvedPhone() {
        UUID tenantId = UUID.randomUUID();
        UUID woId = UUID.randomUUID();
        UUID jobSiteId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();
        UUID technicianId = UUID.randomUUID();

        WorkOrder existing = WorkOrder.builder()
                .id(woId)
                .tenantId(tenantId)
                .jobSiteId(jobSiteId)
                .technicianUserId(technicianId)
                .status(WorkOrderStatus.SCHEDULED)
                .build();
        WorkOrder savedAfter = existing.toBuilder()
                .status(WorkOrderStatus.EN_ROUTE)
                .build();
        when(workOrders.findById(woId)).thenReturn(Mono.just(existing));
        when(workOrders.save(any(WorkOrder.class))).thenReturn(Mono.just(savedAfter));
        when(jobSites.findById(jobSiteId)).thenReturn(Mono.just(JobSite.builder()
                .id(jobSiteId)
                .tenantId(tenantId)
                .contactId(contactId)
                .build()));
        when(contacts.findById(contactId)).thenReturn(Mono.just(Contact.builder()
                .id(contactId)
                .tenantId(tenantId)
                .phones(List.of(
                        PhoneNumber.builder().number("555-extra-junk").build(),
                        PhoneNumber.builder().number("+15555550100").build()))
                .build()));

        WorkOrder patch = WorkOrder.builder().status(WorkOrderStatus.EN_ROUTE).build();
        service.update(woId, patch).block();

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, times(1)).publish(captor.capture());
        DomainEvent emitted = captor.getValue();
        assertThat(emitted.type()).isEqualTo(DomainEventType.WORK_ORDER_EN_ROUTE);
        assertThat(emitted.tenantId()).isEqualTo(tenantId);
        assertThat(emitted.subjectId()).isEqualTo(woId);
        assertThat(emitted.payload().get("contactPhoneE164")).isEqualTo("+15555550100");
        assertThat(emitted.payload().get("workOrderId")).isEqualTo(woId.toString());
        assertThat(emitted.payload().get("jobSiteId")).isEqualTo(jobSiteId.toString());
        assertThat(emitted.payload().get("technicianUserId")).isEqualTo(technicianId.toString());
    }

    @Test
    void transitionToEnRouteWithNoE164Phone_publishesNullPhone() {
        UUID tenantId = UUID.randomUUID();
        UUID woId = UUID.randomUUID();
        UUID jobSiteId = UUID.randomUUID();
        UUID contactId = UUID.randomUUID();

        WorkOrder existing = WorkOrder.builder()
                .id(woId)
                .tenantId(tenantId)
                .jobSiteId(jobSiteId)
                .status(WorkOrderStatus.SCHEDULED)
                .build();
        WorkOrder savedAfter = existing.toBuilder()
                .status(WorkOrderStatus.EN_ROUTE)
                .build();
        when(workOrders.findById(woId)).thenReturn(Mono.just(existing));
        when(workOrders.save(any(WorkOrder.class))).thenReturn(Mono.just(savedAfter));
        when(jobSites.findById(jobSiteId)).thenReturn(Mono.just(JobSite.builder()
                .id(jobSiteId)
                .tenantId(tenantId)
                .contactId(contactId)
                .build()));
        when(contacts.findById(contactId)).thenReturn(Mono.just(Contact.builder()
                .id(contactId)
                .tenantId(tenantId)
                .phones(List.of(PhoneNumber.builder().number("not-e164").build()))
                .build()));

        WorkOrder patch = WorkOrder.builder().status(WorkOrderStatus.EN_ROUTE).build();
        service.update(woId, patch).block();

        ArgumentCaptor<DomainEvent> captor = ArgumentCaptor.forClass(DomainEvent.class);
        verify(events, times(1)).publish(captor.capture());
        assertThat(captor.getValue().payload().get("contactPhoneE164")).isNull();
    }

    @Test
    void saveWithNoStatusChange_doesNotEmit() {
        UUID tenantId = UUID.randomUUID();
        UUID woId = UUID.randomUUID();

        WorkOrder existing = WorkOrder.builder()
                .id(woId)
                .tenantId(tenantId)
                .status(WorkOrderStatus.SCHEDULED)
                .notes("old")
                .build();
        when(workOrders.findById(woId)).thenReturn(Mono.just(existing));
        when(workOrders.save(any(WorkOrder.class)))
                .thenReturn(Mono.just(existing.toBuilder().notes("new").build()));

        WorkOrder patch = WorkOrder.builder().notes("new").build();
        service.update(woId, patch).block();

        verify(events, never()).publish(any());
    }

    @Test
    void resaveWhenAlreadyEnRoute_doesNotEmit() {
        UUID tenantId = UUID.randomUUID();
        UUID woId = UUID.randomUUID();

        WorkOrder existing = WorkOrder.builder()
                .id(woId)
                .tenantId(tenantId)
                .status(WorkOrderStatus.EN_ROUTE)
                .build();
        when(workOrders.findById(woId)).thenReturn(Mono.just(existing));
        when(workOrders.save(any(WorkOrder.class))).thenReturn(Mono.just(existing));

        WorkOrder patch = WorkOrder.builder().status(WorkOrderStatus.EN_ROUTE).build();
        service.update(woId, patch).block();

        verify(events, never()).publish(any());
    }

    @Test
    void transitionOutOfEnRoute_doesNotEmit() {
        UUID tenantId = UUID.randomUUID();
        UUID woId = UUID.randomUUID();

        WorkOrder existing = WorkOrder.builder()
                .id(woId)
                .tenantId(tenantId)
                .status(WorkOrderStatus.EN_ROUTE)
                .build();
        WorkOrder afterSave = existing.toBuilder()
                .status(WorkOrderStatus.ON_SITE)
                .build();
        when(workOrders.findById(woId)).thenReturn(Mono.just(existing));
        when(workOrders.save(any(WorkOrder.class))).thenReturn(Mono.just(afterSave));

        WorkOrder patch = WorkOrder.builder().status(WorkOrderStatus.ON_SITE).build();
        service.update(woId, patch).block();

        verify(events, never()).publish(any());
    }
}
