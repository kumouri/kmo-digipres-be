package com.kumouri.kmodigipresbe.module.homeservices.widget;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 10e — public service-request widget endpoint coverage.
 *
 * <ol>
 *   <li>A valid token + DTO creates a Contact and a DRAFT WorkOrder under the
 *       token's tenant — with a server-assigned {@code workOrderNumber} (the widget
 *       routes through {@code WorkOrderService.create}, so field-service is enabled
 *       here too) — and returns both IDs.</li>
 *   <li>A tampered token rejects with errorCode {@code 1602} (Phase 9b
 *       signature-invalid range).</li>
 *   <li>A token issued for a different widgetType rejects with errorCode
 *       {@code 2700} (Phase 10e widget-type mismatch).</li>
 *   <li>Resubmission with the same email upserts the contact (no duplicate),
 *       still creates a new WorkOrder.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.home-services.enabled=true",
        // field-service enabled so the WorkOrderService bean exists and the widget
        // routes WorkOrder creation through WorkOrderService.create (server-assigned
        // workOrderNumber) rather than the field-service-disabled fallback save.
        "kmosf.modules.field-service.enabled=true"
})
class ServiceRequestWidgetIT {

    @Autowired WebTestClient web;
    @Autowired PublicWidgetTokenService tokens;
    @Autowired ContactRepository contacts;
    @Autowired WorkOrderRepository workOrders;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void setup() {
        mongo.remove(new Query(), Contact.class).block();
        mongo.remove(new Query(), WorkOrder.class).block();
        tenantId = UUID.randomUUID();
    }

    @Test
    void validSubmission_createsContactAndDraftWorkOrder() {
        String token = tokens.issue(tenantId, "service-request", Duration.ofHours(1));
        String email = "leah@example.test";

        ServiceRequestSubmissionResponseDTO response = web.post()
                .uri("/public/widget/service-request/" + token)
                .bodyValue(Map.of(
                        "email", email,
                        "firstName", "Leah",
                        "lastName", "Sample",
                        "phone", "+15555550001",
                        "serviceType", "FURNACE_SERVICE",
                        "notes", "Furnace making clicking noises."))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceRequestSubmissionResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(response).isNotNull();
        assertThat(response.contactId()).isNotNull();
        assertThat(response.workOrderId()).isNotNull();

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("TEST"));

        Contact created = contacts.findById(response.contactId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(created).isNotNull();
        assertThat(created.getTenantId()).isEqualTo(tenantId);
        assertThat(created.getDisplayName()).isEqualTo("Leah Sample");
        assertThat(created.getTags()).contains("public-service-request");

        WorkOrder wo = workOrders.findById(response.workOrderId())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        assertThat(wo).isNotNull();
        assertThat(wo.getTenantId()).isEqualTo(tenantId);
        assertThat(wo.getStatus()).isEqualTo(WorkOrderStatus.DRAFT);
        assertThat(wo.getServiceType()).isEqualTo("FURNACE_SERVICE");
        assertThat(wo.getNotes()).contains("Furnace making clicking noises.");
        assertThat(wo.getWorkOrderNumber())
                .as("widget WorkOrder routed through WorkOrderService.create → server-assigned number")
                .isNotBlank()
                .matches("\\d{4}-\\d{2}-\\d{4}");
    }

    @Test
    void resubmissionWithSameEmail_upsertsContactStillCreatesWorkOrder() {
        String token = tokens.issue(tenantId, "service-request", Duration.ofHours(1));
        String email = "repeat@example.test";
        Map<String, Object> body = Map.of(
                "email", email,
                "firstName", "Repeat",
                "lastName", "Caller",
                "serviceType", "HVAC_TUNEUP");

        ServiceRequestSubmissionResponseDTO first = web.post()
                .uri("/public/widget/service-request/" + token)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceRequestSubmissionResponseDTO.class)
                .returnResult().getResponseBody();

        ServiceRequestSubmissionResponseDTO second = web.post()
                .uri("/public/widget/service-request/" + token)
                .bodyValue(body)
                .exchange()
                .expectStatus().isOk()
                .expectBody(ServiceRequestSubmissionResponseDTO.class)
                .returnResult().getResponseBody();

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(first.contactId()).isEqualTo(second.contactId());
        assertThat(first.workOrderId()).isNotEqualTo(second.workOrderId());

        TenantContext ctx = new TenantContext(tenantId, null, Set.of("TEST"));
        List<Contact> matching = contacts.findByTenantAndEmailAddress(tenantId, email)
                .contextWrite(TenantContextHolder.write(ctx))
                .collectList()
                .block();
        assertThat(matching).hasSize(1);
    }

    @Test
    void tamperedToken_returns401WithSignatureRejection() {
        String token = tokens.issue(tenantId, "service-request", Duration.ofHours(1));
        String tampered = token.substring(0, token.length() - 4) + "XXXX";

        web.post().uri("/public/widget/service-request/" + tampered)
                .bodyValue(Map.of("email", "e@example.test"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").value(o -> assertThat((Integer) o).isBetween(1600, 1699));
    }

    @Test
    void wrongWidgetTypeToken_returns401WithErrorCode2700() {
        String token = tokens.issue(tenantId, "booking", Duration.ofHours(1));

        web.post().uri("/public/widget/service-request/" + token)
                .bodyValue(Map.of("email", "wrong@example.test"))
                .exchange()
                .expectStatus().isUnauthorized()
                .expectBody()
                .jsonPath("$.errorCode").isEqualTo(2700);
    }
}
