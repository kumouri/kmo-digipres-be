package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentStatus;
import com.kumouri.kmodigipresbe.module.frontdesk.model.VisitTypeBucket;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistOffer;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import com.kumouri.kmodigipresbe.service.waitlist.SlotMaterializer;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * T7 (Health "RescheduleFlow") — the frontdesk {@link SlotMaterializer}: when the first inbound YES
 * atomically claims a freed health slot, the E4 {@code WaitlistClaimEngine} dispatches here (by
 * {@code slotType == }{@value #SLOT_TYPE}) and this creates the <strong>real, PHI-free</strong> replacement
 * {@link Appointment} for the winning patient. The engine arbitrates the race; T7 owns the domain write
 * (the {@code SlotMaterializer} SPI contract — registered purely by being a {@code @Bean}, no engine edit).
 *
 * <h2>The headline boundary: PHI-free by construction (fence F1)</h2>
 * The new {@link Appointment} is built from the freed slot's <strong>logistics snapshot only</strong>
 * ({@code contactId}, the opaque {@code providerId}, {@code scheduledStart}/{@code scheduledEnd},
 * {@code durationMinutes}) — and {@code Appointment} has <em>no clinical field to set</em>, so a PHI-free
 * record is the only kind that can exist (the F1 fence the whole flagship rests on). {@code visitTypeBucket}
 * defaults to {@link VisitTypeBucket#OTHER} (a closed logistics enum, never a procedure/diagnosis), and the
 * no-show {@code noShowRisk} is left null (the nightly scorer stamps it later). There is no transcription,
 * no clinical note, no patient-condition free text anywhere on the path.
 *
 * <h2>Slot type + key</h2>
 * {@link #key()} returns {@value #SLOT_TYPE} — the {@link WaitlistSlot#slotType()} the
 * {@code RescheduleGapFillSubscriber} stamps on the freed slot, and the {@link com.kumouri.kmodigipresbe.model.waitlist.WaitlistEntry#getSlotType()}
 * a health waitlister matches. The default {@code supports(slotType)} = {@code key().equals(slotType)}, so
 * this materializer claims only health-appt slots; a salon or other vertical's slot falls through to its own
 * materializer (or the no-op).
 *
 * <h2>Tenant context + best-effort</h2>
 * The engine calls {@code materialize} inside its synthetic {@code TenantContext(tenantId, ..., SYSTEM)}, so
 * the {@code TenantScoped} {@link AppointmentRepository} save is tenant-scoped; the new appointment's
 * {@code tenantId} is also set explicitly from the resolved tenant (the seeder posture). A failure here makes
 * the claim report {@code LOST} (the engine leaves the offer OFFERED for retry/expiry + apologizes — never a
 * silent drop), so this stays best-effort-correct. The FILLED analytics row + the advisory
 * {@code RESCHEDULE_SLOT_FILLED} event are best-effort breadcrumbs that never fail the materialize.
 */
@Slf4j
public class FrontDeskSlotMaterializer implements SlotMaterializer {

    /**
     * The slot type this materializer owns — the dispatch key the {@code WaitlistClaimEngine} matches and the
     * {@code RescheduleGapFillSubscriber} stamps on a freed health slot + the health waitlist entries.
     */
    public static final String SLOT_TYPE = "health-appt";

    /** The record kind echoed in the {@code MaterializedRef} + the {@code WAITLIST_ENGINE_SLOT_CLAIMED} event. */
    public static final String REF_TYPE = "APPOINTMENT";

    private static final int DEFAULT_DURATION_MINUTES = 30;

    private final AppointmentRepository appointments;
    private final RescheduleAnalyticsService analytics;
    private final DomainEventPublisher events;

    public FrontDeskSlotMaterializer(AppointmentRepository appointments,
                                     RescheduleAnalyticsService analytics,
                                     DomainEventPublisher events) {
        this.appointments = appointments;
        this.analytics = analytics;
        this.events = events;
    }

    @Override
    public String key() {
        return SLOT_TYPE;
    }

    @Override
    public Mono<MaterializedRef> materialize(UUID tenantId, WaitlistOffer claimedOffer, WaitlistSlot slot) {
        Appointment toSave = buildPhiFreeAppointment(tenantId, claimedOffer, slot);
        return appointments.save(toSave)
                .flatMap(saved -> analytics.record(tenantId, RescheduleFillEvent.FILLED)
                        .doOnSuccess(v -> emitFilled(tenantId, slot, saved))
                        .thenReturn(MaterializedRef.of(REF_TYPE, saved.getId())))
                .doOnSuccess(ref -> log.info(
                        "RescheduleFlow: filled freed slot {} for contact {} (tenant {}) -> appointment {}",
                        slot.slotKey(), claimedOffer.getContactId(), tenantId, ref.refId()));
    }

    /**
     * Build the replacement {@link Appointment} from the freed slot's logistics snapshot — PHI-free by
     * construction (fence F1: there is no clinical field on {@link Appointment} to set). The end time is the
     * slot end if present, else derived from the duration (default {@value #DEFAULT_DURATION_MINUTES}m).
     */
    private Appointment buildPhiFreeAppointment(UUID tenantId, WaitlistOffer offer, WaitlistSlot slot) {
        Instant start = slot.slotStart() != null ? slot.slotStart() : offer.getSlotStart();
        int duration = slot.durationMinutes() > 0 ? slot.durationMinutes()
                : (offer.getDurationMinutes() > 0 ? offer.getDurationMinutes() : DEFAULT_DURATION_MINUTES);
        Instant end = slot.slotEnd() != null ? slot.slotEnd()
                : (offer.getSlotEnd() != null ? offer.getSlotEnd()
                        : (start != null ? start.plus(Duration.ofMinutes(duration)) : null));
        UUID providerId = slot.providerId() != null ? slot.providerId() : offer.getProviderId();
        return Appointment.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .contactId(offer.getContactId())
                .providerId(providerId)
                .scheduledStart(start)
                .scheduledEnd(end)
                .status(AppointmentStatus.SCHEDULED)
                .visitTypeBucket(VisitTypeBucket.OTHER)
                .noShowRisk(null)
                .build();
    }

    private void emitFilled(UUID tenantId, WaitlistSlot slot, Appointment saved) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("slotKey", slot.slotKey());
        payload.put("appointmentId", saved.getId());
        payload.put("contactId", saved.getContactId());
        events.publish(DomainEvent.of(
                DomainEventType.RESCHEDULE_SLOT_FILLED, tenantId, saved.getId(), payload));
    }
}
