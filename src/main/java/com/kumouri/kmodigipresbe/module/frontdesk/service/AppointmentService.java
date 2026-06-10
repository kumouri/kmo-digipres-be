package com.kumouri.kmodigipresbe.module.frontdesk.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * FrontDesk IQ (FD-1) — CRUD for {@link Appointment}, the staff console surface (the {@code ListingService}
 * posture, minus the salon deposit/loyalty concepts).
 *
 * <p>All reads/writes resolve the tenant from the Reactor {@code TenantContext} and stamp it onto the
 * entity, so an appointment can never be created or fetched for a foreign tenant ({@code 4276} on a missing
 * / not-owned appointment). Hand-constructed as a {@code @Bean} by {@code FrontDeskAutoConfiguration} (no
 * {@code @Service} annotation) so it exists only when the module is enabled — blast-radius zero.
 *
 * <p><strong>PHI boundary (fence F1):</strong> the only fields accepted are scheduling logistics. There is
 * no clinical field on {@link Appointment} to set, so a caller cannot smuggle PHI in through create/update.
 * {@code visitTypeBucket} is normalized through the closed {@link VisitTypeBucket} enum (no free text).
 *
 * <p><strong>T7 (Health "RescheduleFlow") additive cancel-event emit (the only T7 frontdesk-core edit):</strong>
 * there is no dedicated {@code cancel()} method — a cancel happens through {@link #update} with
 * {@code status=CANCELLED}. So {@code update} emits {@link DomainEventType#APPOINTMENT_CANCELLED} ONLY on a
 * real {@code SCHEDULED|CONFIRMED -> CANCELLED} transition (the additive {@code SalonBookingService.cancel()}
 * emit precedent — a no-op when the status did not actually move to CANCELLED). The emit is the reliable
 * freed-slot trigger for the T7 {@code RescheduleGapFillSubscriber} (gap-fill the freed slot from the
 * waitlist). It is purely advisory + PHI-free (logistics-only payload — no clinical field exists, fence F1)
 * and a harmless no-op for a tenant without the T7 modules (no subscriber runs). Every other {@code update}
 * outcome is byte-identical to before (no event).
 */
public class AppointmentService {

    private final AppointmentRepository appointments;
    private final DomainEventPublisher events;

    public AppointmentService(AppointmentRepository appointments, DomainEventPublisher events) {
        this.appointments = appointments;
        this.events = events;
    }

    /**
     * Creates an appointment scoped to the caller's tenant. Server-side validation: a {@code contactId} and
     * a {@code scheduledStart} are required ({@code 4277} otherwise — there is nothing to score without
     * them). The id/tenantId are assigned here; the no-show risk is left null (the nightly scorer stamps it
     * on upcoming appointments).
     */
    public Mono<Appointment> create(Appointment appointment) {
        return validate(appointment).then(TenantContextHolder.required().flatMap(ctx -> {
            Appointment toSave = appointment.toBuilder()
                    .id(UUID.randomUUID())
                    .tenantId(ctx.tenantId())
                    .visitTypeBucket(appointment.getVisitTypeBucket() != null
                            ? appointment.getVisitTypeBucket() : VisitTypeBucket.OTHER)
                    .noShowRisk(null)
                    .build();
            return appointments.save(toSave);
        }));
    }

    public Flux<Appointment> list() {
        return TenantContextHolder.required()
                .flatMapMany(ctx -> appointments.findByTenantIdOrderByScheduledStartDesc(ctx.tenantId()));
    }

    public Mono<Appointment> get(UUID id) {
        return TenantContextHolder.required()
                .flatMap(ctx -> appointments.findByIdAndTenantId(id, ctx.tenantId()))
                .switchIfEmpty(Mono.error(() -> new DigiPresBeException(
                        "Appointment not found", 4276, 404)));
    }

    /**
     * Patches the mutable logistics fields of an appointment. Returns {@code 4276} if the appointment does
     * not exist for the current tenant. The id, tenantId, version, timestamps, and the scorer-owned
     * {@code noShowRisk} stamp are preserved (optimistic-lock safe; the scorer is the only writer of risk).
     */
    public Mono<Appointment> update(UUID id, Appointment patch) {
        return get(id).flatMap(existing -> {
            AppointmentStatus priorStatus = existing.getStatus();
            Appointment updated = existing.toBuilder()
                    .contactId(patch.getContactId() != null ? patch.getContactId() : existing.getContactId())
                    .providerId(patch.getProviderId() != null ? patch.getProviderId() : existing.getProviderId())
                    .scheduledStart(patch.getScheduledStart() != null
                            ? patch.getScheduledStart() : existing.getScheduledStart())
                    .scheduledEnd(patch.getScheduledEnd() != null
                            ? patch.getScheduledEnd() : existing.getScheduledEnd())
                    .status(patch.getStatus() != null ? patch.getStatus() : existing.getStatus())
                    .visitTypeBucket(patch.getVisitTypeBucket() != null
                            ? patch.getVisitTypeBucket() : existing.getVisitTypeBucket())
                    .insuranceVerificationPending(patch.isInsuranceVerificationPending())
                    .lastVisitAt(patch.getLastVisitAt() != null ? patch.getLastVisitAt() : existing.getLastVisitAt())
                    .reminderCount(patch.getReminderCount() > 0 ? patch.getReminderCount() : existing.getReminderCount())
                    .calComBookingUid(patch.getCalComBookingUid() != null
                            ? patch.getCalComBookingUid() : existing.getCalComBookingUid())
                    .build();
            return appointments.save(updated)
                    .doOnNext(saved -> maybeEmitCancelled(priorStatus, saved));
        });
    }

    /**
     * T7 additive emit: fire {@link DomainEventType#APPOINTMENT_CANCELLED} ONLY on a real
     * {@code SCHEDULED|CONFIRMED -> CANCELLED} transition (the {@code SalonBookingService.cancel()}
     * precedent). A status that was already CANCELLED / terminal, or an update that did not move to
     * CANCELLED, emits nothing — byte-identical to before T7. PHI-free payload (logistics only — there is
     * no clinical field on {@link Appointment} to leak, fence F1).
     */
    private void maybeEmitCancelled(AppointmentStatus priorStatus, Appointment saved) {
        boolean transitionedToCancelled = saved.getStatus() == AppointmentStatus.CANCELLED
                && (priorStatus == AppointmentStatus.SCHEDULED
                        || priorStatus == AppointmentStatus.CONFIRMED);
        if (!transitionedToCancelled) {
            return;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("appointmentId", saved.getId());
        payload.put("contactId", saved.getContactId());
        payload.put("providerId", saved.getProviderId());
        payload.put("scheduledStart", saved.getScheduledStart());
        payload.put("scheduledEnd", saved.getScheduledEnd());
        payload.put("visitTypeBucket", saved.getVisitTypeBucket());
        events.publish(DomainEvent.of(
                DomainEventType.APPOINTMENT_CANCELLED,
                saved.getTenantId(),
                saved.getId(),
                payload));
    }

    private Mono<Void> validate(Appointment appointment) {
        if (appointment == null
                || appointment.getContactId() == null
                || appointment.getScheduledStart() == null) {
            return Mono.error(new DigiPresBeException(
                    "An appointment requires a contactId and a scheduledStart", 4277, 400));
        }
        return Mono.empty();
    }
}
