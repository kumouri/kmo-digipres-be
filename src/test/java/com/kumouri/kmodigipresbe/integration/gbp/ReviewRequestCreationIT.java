package com.kumouri.kmodigipresbe.integration.gbp;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.integration.ReviewRequest;
import com.kumouri.kmodigipresbe.model.integration.ReviewSubjectType;
import com.kumouri.kmodigipresbe.model.project.Project;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.repository.project.ProjectRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.TestPropertySource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E3 Review Engine — ReviewRequestCreationIT: review-REQUEST creation on completion. Drives
 * {@code ReviewRequestService.handle(DomainEvent)} deterministically with synthetic completion events
 * (the {@code RiskTieredPreventionService.handle} visible-for-test precedent — no live event bus / no
 * sleep). Asserts attribution + creation idempotency + no-contact skip.
 *
 * <h2>Cases</h2>
 * <ul>
 *   <li>{@code BOOKING_COMPLETED} → one PENDING request attributed {@code (STAFF, staffMemberId)} with
 *       the payload contact + due ~now+delay;</li>
 *   <li>{@code MILESTONE_COMPLETED} → one PENDING request attributed {@code (PROJECT, projectId)} with
 *       the contact resolved from {@code Project.primaryContactId};</li>
 *   <li>the same completion fired twice → still exactly one request (explicit-boolean + unique index);</li>
 *   <li>a {@code BOOKING_COMPLETED} with no contactId → no request; a {@code MILESTONE_COMPLETED} for a
 *       project with no primaryContactId → no request.</li>
 * </ul>
 *
 * <p>Shard-safe: no mocks, no WireMock, self-clean {@code mongo.remove}.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class ReviewRequestCreationIT {

    @Autowired ReviewRequestService service;
    @Autowired ReviewRequestRepository reviewRequests;
    @Autowired ProjectRepository projects;
    @Autowired TenantRepository tenants;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), Tenant.class).block();

        tenantId = UUID.randomUUID();
        tenants.save(Tenant.builder().id(tenantId).slug("review-req-it-" + tenantId)
                .displayName("Review Request IT").status(Tenant.TenantStatus.ACTIVE).build()).block();
    }

    @AfterEach
    void cleanup() {
        mongo.remove(new Query(), ReviewRequest.class).block();
        mongo.remove(new Query(), Project.class).block();
        mongo.remove(new Query(), Tenant.class).block();
    }

    private DomainEvent bookingCompleted(UUID bookingId, UUID contactId, UUID staffMemberId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("bookingId", bookingId);
        payload.put("contactId", contactId);
        payload.put("staffMemberId", staffMemberId);
        return DomainEvent.of(DomainEventType.BOOKING_COMPLETED, tenantId, bookingId, payload);
    }

    private DomainEvent milestoneCompleted(UUID projectId, UUID milestoneId) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("projectId", projectId.toString());
        payload.put("milestoneId", milestoneId.toString());
        return DomainEvent.of(DomainEventType.MILESTONE_COMPLETED, tenantId, milestoneId, payload);
    }

    // -------------------------------------------------------------------------
    // BOOKING_COMPLETED -> (STAFF, staffMemberId) PENDING request
    // -------------------------------------------------------------------------

    @Test
    void bookingCompleted_createsStaffAttributedPendingRequest() {
        UUID contactId = UUID.randomUUID();
        UUID stylistId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        service.handle(bookingCompleted(bookingId, contactId, stylistId)).block();

        List<ReviewRequest> rows = mongo.findAll(ReviewRequest.class).collectList().block();
        assertThat(rows).hasSize(1);
        ReviewRequest r = rows.get(0);
        assertThat(r.getStatus()).isEqualTo(ReviewRequest.Status.PENDING);
        assertThat(r.getSubjectType()).isEqualTo(ReviewSubjectType.STAFF);
        assertThat(r.getSubjectId()).isEqualTo(stylistId);
        assertThat(r.getContactId()).isEqualTo(contactId);
        assertThat(r.getSourceEventType()).isEqualTo(DomainEventType.BOOKING_COMPLETED);
        assertThat(r.getDueAt()).isNotNull();
    }

    @Test
    void bookingCompleted_noStaffMember_attributesToOtherKeyedOnBooking() {
        UUID contactId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        service.handle(bookingCompleted(bookingId, contactId, null)).block();

        List<ReviewRequest> rows = mongo.findAll(ReviewRequest.class).collectList().block();
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).getSubjectType()).isEqualTo(ReviewSubjectType.OTHER);
        assertThat(rows.get(0).getSubjectId()).isEqualTo(bookingId);
        assertThat(rows.get(0).getContactId()).isEqualTo(contactId);
    }

    // -------------------------------------------------------------------------
    // MILESTONE_COMPLETED -> (PROJECT, projectId) PENDING request, contact from Project
    // -------------------------------------------------------------------------

    @Test
    void milestoneCompleted_createsProjectAttributedRequest_contactFromProject() {
        UUID contactId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();
        UUID milestoneId = UUID.randomUUID();
        projects.save(Project.builder()
                .id(projectId).tenantId(tenantId).name("Mole job")
                .primaryContactId(contactId)
                .build()).block();

        service.handle(milestoneCompleted(projectId, milestoneId)).block();

        List<ReviewRequest> rows = mongo.findAll(ReviewRequest.class).collectList().block();
        assertThat(rows).hasSize(1);
        ReviewRequest r = rows.get(0);
        assertThat(r.getSubjectType()).isEqualTo(ReviewSubjectType.PROJECT);
        assertThat(r.getSubjectId()).isEqualTo(projectId);
        assertThat(r.getContactId()).isEqualTo(contactId);
        assertThat(r.getSourceEventType()).isEqualTo(DomainEventType.MILESTONE_COMPLETED);
    }

    // -------------------------------------------------------------------------
    // Idempotent — same completion twice -> still one request
    // -------------------------------------------------------------------------

    @Test
    void sameCompletionTwice_isIdempotent() {
        UUID contactId = UUID.randomUUID();
        UUID stylistId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();

        service.handle(bookingCompleted(bookingId, contactId, stylistId)).block();
        service.handle(bookingCompleted(bookingId, contactId, stylistId)).block();

        assertThat(mongo.findAll(ReviewRequest.class).collectList().block()).hasSize(1);
    }

    // -------------------------------------------------------------------------
    // No resolvable contact -> no request
    // -------------------------------------------------------------------------

    @Test
    void bookingCompleted_noContact_createsNothing() {
        service.handle(bookingCompleted(UUID.randomUUID(), null, UUID.randomUUID())).block();
        assertThat(mongo.findAll(ReviewRequest.class).collectList().block()).isEmpty();
    }

    @Test
    void milestoneCompleted_projectWithoutPrimaryContact_createsNothing() {
        UUID projectId = UUID.randomUUID();
        projects.save(Project.builder()
                .id(projectId).tenantId(tenantId).name("No-contact job")
                .build()).block(); // no primaryContactId

        service.handle(milestoneCompleted(projectId, UUID.randomUUID())).block();
        assertThat(mongo.findAll(ReviewRequest.class).collectList().block()).isEmpty();
    }
}
