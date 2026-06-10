package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.TestcontainersConfiguration;
import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.tenant.Tenant;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.module.frontdesk.service.AppointmentService;
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

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T7 (Health "RescheduleFlow") — proves the ONLY additive frontdesk-core edit: {@code AppointmentService.update()}
 * emits {@link DomainEventType#APPOINTMENT_CANCELLED} on a real SCHEDULED|CONFIRMED → CANCELLED transition,
 * and emits nothing on any other update. The {@code GapFillWaitlistIT.cancel_emitsBookingCancelled} precedent
 * (subscribe to the bus + await). This is the surgical-seam guard — every non-cancel update stays
 * byte-identical (no event).
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@TestPropertySource(properties = {
        "kmosf.modules.frontdesk.enabled=true",
        "kmosf.modules.waitlist.enabled=true",
        "kmosf.quartz.proof-job.enabled=false",
        "kmosf.recurring-invoice.spawn-job.enabled=false"
})
class AppointmentCancelEmitIT {

    @Autowired AppointmentService appointmentService;
    @Autowired DomainEventPublisher eventPublisher;
    @Autowired ReactiveMongoTemplate mongo;

    private UUID tenantId;
    private TenantContext ctx;

    @BeforeEach
    void seed() {
        mongo.remove(new Query(), Appointment.class).block();
        mongo.remove(new Query(), Tenant.class).block();
        tenantId = UUID.randomUUID();
        ctx = new TenantContext(tenantId, null, Set.of("STAFF", "ADMIN"));
        mongo.save(Tenant.builder().id(tenantId).slug("appt-cancel-emit-it-" + tenantId)
                .displayName("Appt Cancel Emit IT").status(Tenant.TenantStatus.ACTIVE)
                .enabledModules(Set.of("frontdesk", "waitlist"))
                .aiBudgetUsd(BigDecimal.ZERO)
                .build()).block();
    }

    // ── update -> CANCELLED emits APPOINTMENT_CANCELLED with the logistics payload ──

    @Test
    void updateToCancelled_emitsAppointmentCancelled() {
        UUID apptId = createScheduled();

        List<DomainEvent> observed = subscribe();
        try {
            Appointment patch = Appointment.builder()
                    .status(AppointmentStatus.CANCELLED)
                    .build();
            Appointment after = appointmentService.update(apptId, patch)
                    .contextWrite(TenantContextHolder.write(ctx))
                    .block();

            assertThat(after.getStatus()).isEqualTo(AppointmentStatus.CANCELLED);
            org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
                assertThat(observed).hasSize(1);
                var p = observed.get(0).payload();
                assertThat(p.get("appointmentId")).isEqualTo(apptId);
                assertThat(p).containsKeys("contactId", "scheduledStart", "scheduledEnd", "visitTypeBucket");
                // PHI-free payload — visitTypeBucket is the closed logistics enum, never a clinical field.
                assertThat(p.get("visitTypeBucket").toString()).isEqualTo("OTHER");
            });
        } finally {
            // give the bus a beat to settle before the next test wipes
        }
    }

    // ── a non-cancel update emits NOTHING (the surgical seam) ─────────────────────

    @Test
    void updateNotToCancelled_emitsNothing() {
        UUID apptId = createScheduled();

        List<DomainEvent> observed = subscribe();
        Appointment patch = Appointment.builder()
                .status(AppointmentStatus.CONFIRMED)   // SCHEDULED -> CONFIRMED, not a cancel
                .build();
        appointmentService.update(apptId, patch)
                .contextWrite(TenantContextHolder.write(ctx))
                .block();

        // No APPOINTMENT_CANCELLED for a non-cancel transition (byte-identical to pre-T7).
        try {
            Thread.sleep(500); // brief settle — assert the bus stayed quiet
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        assertThat(observed).isEmpty();
    }

    // ── re-cancelling an already-CANCELLED appointment emits nothing ─────────────

    @Test
    void reCancel_alreadyCancelled_emitsNothing() {
        UUID apptId = createScheduled();
        // First cancel (emits once) — then clear and re-cancel.
        appointmentService.update(apptId, Appointment.builder().status(AppointmentStatus.CANCELLED).build())
                .contextWrite(TenantContextHolder.write(ctx)).block();

        List<DomainEvent> observed = subscribe();
        appointmentService.update(apptId, Appointment.builder().status(AppointmentStatus.CANCELLED).build())
                .contextWrite(TenantContextHolder.write(ctx)).block();

        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        // The prior status was already CANCELLED → not a SCHEDULED|CONFIRMED -> CANCELLED transition → no emit.
        assertThat(observed).isEmpty();
    }

    private UUID createScheduled() {
        Appointment created = appointmentService.create(Appointment.builder()
                        .contactId(UUID.randomUUID())
                        .scheduledStart(Instant.now().plus(Duration.ofHours(3)))
                        .visitTypeBucket(VisitTypeBucket.OTHER)
                        .build())
                .contextWrite(TenantContextHolder.write(ctx))
                .block();
        return created.getId();
    }

    private List<DomainEvent> subscribe() {
        List<DomainEvent> observed = new CopyOnWriteArrayList<>();
        eventPublisher.stream()
                .filter(e -> DomainEventType.APPOINTMENT_CANCELLED.equals(e.type()))
                .filter(e -> tenantId.equals(e.tenantId()))
                .subscribe(observed::add);
        return observed;
    }
}
