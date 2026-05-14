package com.kumouri.kmodigipresbe.module.homeservices.automation;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.WorkflowRule;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Phase 10e end-to-end: full RuleEngine path from WorkOrder PATCH ->
 * WorkOrderService.update -> DomainEventPublisher (WORK_ORDER_EN_ROUTE) ->
 * RuleEngine -> RuleActionDispatcher (SEND_SMS) -> TwilioSmsService.
 *
 * <p>{@link TwilioSmsService} is the seam mocked here — wiring WireMock into
 * the Twilio service requires a config-driven base URL it does not currently
 * expose, and the dispatcher's contract is "calls {@code twilioSms.sendSms(...)}",
 * which a Mockito spy verifies precisely without standing up a fake server.
 *
 * <p>Acceptance criterion 3 (SMS within 30s of EN_ROUTE) is verified via
 * {@code Awaitility} — the rule engine processes events asynchronously on a
 * parallel scheduler.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        "kmosf.modules.field-service.enabled=true"
})
class OnTheWayDispatchIT {

    @Autowired TenantRepository tenants;
    @Autowired ContactRepository contacts;
    @Autowired JobSiteRepository jobSites;
    @Autowired WorkOrderRepository workOrders;
    @Autowired WorkOrderService workOrderService;
    @Autowired OnTheWaySmsAutomation seeder;
    @Autowired ReactiveMongoTemplate mongo;

    @MockitoBean TwilioSmsService twilio;

    private final AtomicReference<String> lastTo = new AtomicReference<>();
    private final AtomicReference<String> lastBody = new AtomicReference<>();

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Tenant.class).block();
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), JobSite.class).block();
        mongo.remove(new Query(), WorkOrder.class).block();
        mongo.remove(new Query(), WorkflowRule.class).block();
        lastTo.set(null);
        lastBody.set(null);

        when(twilio.sendSms(any(SmsCommunicationRequest.class)))
                .thenAnswer(inv -> {
                    SmsCommunicationRequest req = inv.getArgument(0);
                    lastTo.set(req.to() == null ? null : req.to().e164());
                    lastBody.set(req.body());
                    return Mono.just(true);
                });

        Tenant t = Tenant.builder()
                .id(UUID.randomUUID())
                .slug("dispatch-test-tenant")
                .displayName("Dispatch Test Tenant")
                .status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of(
                        HomeServicesAutoConfiguration.MODULE_KEY,
                        "field-service"))
                .build();
        tenants.save(t).block();
        tenantId = t.getId();
        ctx = new TenantContext(tenantId, UUID.randomUUID(), Set.of("STAFF"));

        seeder.seedAll().block();
    }

    @Test
    void workOrderTransitionToEnRoute_dispatchesSmsViaTwilio() {
        // Seed contact with E.164 phone, job site linked to contact, scheduled WO.
        Contact contact = contacts.save(Contact.builder()
                        .type(ContactType.PERSON)
                        .firstName("Pat")
                        .lastName("Customer")
                        .displayName("Pat Customer")
                        .emails(List.of(new EmailContact("pat@example.test")))
                        .phones(List.of(PhoneNumber.builder()
                                .number("+15555550100").label("mobile").build()))
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        JobSite site = jobSites.save(JobSite.builder()
                        .contactId(contact.getId())
                        .label("Pat's House")
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        WorkOrder wo = workOrders.save(WorkOrder.builder()
                        .jobSiteId(site.getId())
                        .status(WorkOrderStatus.SCHEDULED)
                        .serviceType("HVAC_TUNEUP")
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // PATCH the WorkOrder to EN_ROUTE — this is the trigger.
        WorkOrder patch = WorkOrder.builder().status(WorkOrderStatus.EN_ROUTE).build();
        workOrderService.update(wo.getId(), patch)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // Acceptance criterion 3: SMS within 30s of EN_ROUTE.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            assertThat(lastTo.get()).isEqualTo("+15555550100");
            assertThat(lastBody.get()).isNotNull();
            assertThat(lastBody.get()).contains("technician is on the way");
        });
    }
}
