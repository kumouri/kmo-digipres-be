package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.waitlist.WaitlistSlot;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.waitlist.WaitlistAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T7 (Health "RescheduleFlow") — the freed-slot trigger: a {@code @PostConstruct} subscriber on
 * {@link DomainEventType#APPOINTMENT_CANCELLED} (the additive {@code AppointmentService.update()} emit) that
 * projects the cancelled health {@link com.kumouri.kmodigipresbe.module.frontdesk.model.Appointment} into a
 * {@link WaitlistSlot} and calls the E4 {@link GapFillEngine#gapFill} to offer the freed slot to the
 * waitlist. The chairfill CF-3 {@code GapFillService} subscriber precedent (the
 * {@code events.stream().filter(type).flatMap(handle)} pattern + synthetic {@link TenantContext}), but it
 * delegates the ranking/offer/SMS to the <strong>generic E4 engine</strong> (chairfill keeps its own,
 * untouched).
 *
 * <h2>Flow</h2>
 * <ol>
 *   <li>A health {@code Appointment} cancels → {@code APPOINTMENT_CANCELLED} carries the freed slot's
 *       logistics ({@code appointmentId}, {@code providerId}, {@code scheduledStart}/{@code scheduledEnd}).</li>
 *   <li>Build a {@link WaitlistSlot} with {@code slotType="health-appt"} (the
 *       {@link FrontDeskSlotMaterializer} dispatch key) + {@code slotKey=<appointmentId>} (the per-slot claim
 *       doc {@code _id} base — so two YESs for the same freed appointment resolve to exactly one winner).</li>
 *   <li>Record a CANCELLATION fill-funnel row + emit {@code RESCHEDULE_GAP_FILL_STARTED} (best-effort), then
 *       call {@code GapFillEngine.gapFill(tenantId, slot)} (rank → top-N time-boxed offers → SMS).</li>
 * </ol>
 *
 * <h2>Defense-in-depth (both modules)</h2>
 * The only real emitter of {@code APPOINTMENT_CANCELLED} is {@code AppointmentService.update()}, which is a
 * {@code @Bean} only when {@code frontdesk} is on — but this subscriber additionally hard-no-ops unless the
 * tenant's {@code enabledModules} contains BOTH {@code frontdesk} AND {@code waitlist} (the CF-3
 * {@code GapFillService.process} module-membership gate, doubled for the both-module T7). {@code GapFillEngine}
 * also re-checks {@code waitlist} membership itself, so a misconfigured tenant sends zero SMS.
 *
 * <p><strong>Best-effort + blast-radius zero:</strong> a per-event failure is caught + logged so one bad
 * cancel never aborts the subscription. PHI-free: the payload + the {@code WaitlistSlot} are logistics only
 * (no clinical field exists, fence F1); the offer SMS is the engine's generic deterministic template.
 */
@Slf4j
public class RescheduleGapFillSubscriber {

    private final DomainEventPublisher events;
    private final TenantRepository tenants;
    private final GapFillEngine gapFillEngine;
    private final RescheduleAnalyticsService analytics;

    private static final int DEFAULT_DURATION_MINUTES = 30;

    public RescheduleGapFillSubscriber(DomainEventPublisher events,
                                       TenantRepository tenants,
                                       GapFillEngine gapFillEngine,
                                       RescheduleAnalyticsService analytics) {
        this.events = events;
        this.tenants = tenants;
        this.gapFillEngine = gapFillEngine;
        this.analytics = analytics;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.APPOINTMENT_CANCELLED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("RescheduleFlow: error processing APPOINTMENT_CANCELLED for tenant {}",
                                    e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code APPOINTMENT_CANCELLED} end-to-end and complete with
     * the number of offers sent (so an IT can drive it deterministically, the CF-3 {@code handle} posture).
     */
    public Mono<Integer> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        Map<String, Object> p = event.payload() == null ? Map.of() : event.payload();
        UUID freedAppointmentId = asUuid(p.get("appointmentId"));
        if (tenantId == null || freedAppointmentId == null) {
            return Mono.just(0);
        }
        UUID providerId = asUuid(p.get("providerId"));
        Instant slotStart = asInstant(p.get("scheduledStart"));
        Instant slotEnd = asInstant(p.get("scheduledEnd"));
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return process(tenantId, freedAppointmentId, providerId, slotStart, slotEnd)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Integer> process(UUID tenantId, UUID freedAppointmentId, UUID providerId,
                                  Instant slotStart, Instant slotEnd) {
        // Defense-in-depth: gap-fill runs only for a tenant that has enabled BOTH frontdesk AND waitlist
        // (the CF-3 GapFillService module-membership gate, doubled for the both-module T7).
        return tenants.findById(tenantId)
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(FrontDeskAutoConfiguration.MODULE_KEY)
                        && t.getEnabledModules().contains(WaitlistAutoConfiguration.MODULE_KEY))
                .flatMap(t -> runGapFill(tenantId, freedAppointmentId, providerId, slotStart, slotEnd))
                .defaultIfEmpty(0);
    }

    private Mono<Integer> runGapFill(UUID tenantId, UUID freedAppointmentId, UUID providerId,
                                     Instant slotStart, Instant slotEnd) {
        WaitlistSlot slot = buildSlot(freedAppointmentId, providerId, slotStart, slotEnd);
        // Record the cancellation funnel stage + advisory event (best-effort), then run the gap-fill.
        return analytics.record(tenantId, RescheduleFillEvent.CANCELLATION)
                .then(gapFillEngine.gapFill(tenantId, slot))
                .flatMap(offersSent -> {
                    emitGapFillStarted(tenantId, slot, offersSent);
                    Mono<Void> offerStage = offersSent > 0
                            ? analytics.record(tenantId, RescheduleFillEvent.OFFER)
                            : Mono.empty();
                    return offerStage.thenReturn(offersSent);
                });
    }

    /**
     * Project the cancelled appointment into a PHI-free {@link WaitlistSlot}: {@code slotType="health-appt"}
     * (the materializer dispatch + waitlist-entry match key), {@code slotKey=<appointmentId>} (the per-slot
     * claim doc base). End time falls back to the duration when absent.
     */
    private WaitlistSlot buildSlot(UUID freedAppointmentId, UUID providerId,
                                   Instant slotStart, Instant slotEnd) {
        int durationMinutes = (slotStart != null && slotEnd != null && slotEnd.isAfter(slotStart))
                ? (int) Duration.between(slotStart, slotEnd).toMinutes()
                : DEFAULT_DURATION_MINUTES;
        Instant end = slotEnd != null ? slotEnd
                : (slotStart != null ? slotStart.plus(Duration.ofMinutes(durationMinutes)) : null);
        return WaitlistSlot.builder()
                .slotType(FrontDeskSlotMaterializer.SLOT_TYPE)
                .slotKey(freedAppointmentId.toString())
                .providerId(providerId)
                .slotStart(slotStart)
                .slotEnd(end)
                .durationMinutes(durationMinutes)
                .build();
    }

    private void emitGapFillStarted(UUID tenantId, WaitlistSlot slot, int offersSent) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("appointmentId", slot.slotKey());
        payload.put("slotKey", slot.slotKey());
        payload.put("providerId", slot.providerId());
        payload.put("offersSent", offersSent);
        events.publish(DomainEvent.of(
                DomainEventType.RESCHEDULE_GAP_FILL_STARTED, tenantId, null, payload));
    }

    // ── payload coercion helpers (the CF-3 GapFillService posture — events round-trip via the bus/webhook) ──

    private static UUID asUuid(Object raw) {
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Instant asInstant(Object raw) {
        if (raw instanceof Instant i) return i;
        if (raw instanceof String s) {
            try {
                return Instant.parse(s);
            } catch (Exception ignored) {
                return null;
            }
        }
        return null;
    }
}
