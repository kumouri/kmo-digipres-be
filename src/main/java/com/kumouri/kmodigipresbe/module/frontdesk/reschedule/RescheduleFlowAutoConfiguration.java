package com.kumouri.kmodigipresbe.module.frontdesk.reschedule;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.waitlist.WaitlistAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistClaimEngine;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T7 (Health "RescheduleFlow") — deploys the shipped E4 Gap-Fill Waitlist engine to the frontdesk (health)
 * vertical, PHI-free (the health twin of the T2/T4 both-module deployments). The engine itself is NOT
 * reimplemented (rank / offer / atomic claim / SMS are all the E4 services, reused byte-equivalent); T7 adds
 * the consumer pieces: the {@link FrontDeskSlotMaterializer} (creates the real PHI-free Appointment on a
 * winning claim), the {@link RescheduleGapFillSubscriber} (cancel → gap-fill), the
 * {@link RescheduleWaitlistIntentHandler} (inbound YES → claim, via the E2 responder seam), and the
 * {@link RescheduleAnalyticsService} fill-funnel ledger.
 *
 * <h2>Both-modules gate (compose frontdesk AND waitlist — the T2/T4 posture)</h2>
 * <ul>
 *   <li>{@link ConditionalOnProperty}{@code (kmosf.modules.frontdesk.enabled)} on the class — the frontdesk
 *       gate (matchIfMissing defaults to <strong>false</strong>, so this whole config is OFF unless frontdesk
 *       is explicitly enabled — the {@link FrontDeskAutoConfiguration} posture);</li>
 *   <li>{@link ConditionalOnBean}{@code (WaitlistClaimEngine.class)} on the class — the waitlist gate: the E4
 *       engine beans (incl. {@link WaitlistClaimEngine} + {@link GapFillEngine}) exist <strong>only</strong>
 *       when {@code kmosf.modules.waitlist.enabled} (matchIfMissing=true) is on (they are {@code @Bean}s in
 *       {@link WaitlistAutoConfiguration}). A deployment that disables waitlist
 *       ({@code kmosf.modules.waitlist.enabled=false}) has no engine bean → this whole config is absent.
 *       {@code @AutoConfiguration(after=...)} guarantees both prerequisite configs are processed first.</li>
 * </ul>
 * Net: frontdesk ON + waitlist ON ⇒ active; either OFF ⇒ absent (a hard no-op). Per-tenant membership is then
 * enforced by {@code TenantModuleRegistry.requireEnabled} for both keys in {@link RescheduleController} (and
 * re-checked on the event path by {@link RescheduleGapFillSubscriber} + the engine's own membership gate).
 *
 * <h2>Self-registering consumers — no engine / no router edit</h2>
 * {@link FrontDeskSlotMaterializer} is auto-discovered by the {@code WaitlistClaimEngine}'s
 * {@code List<SlotMaterializer>} inject (it dispatches by {@code slotType="health-appt"}); the
 * {@link RescheduleWaitlistIntentHandler} is auto-discovered by the E2 {@code InboundIntentRouter}'s
 * {@code List<IntentHandler>} inject — both registered purely by being beans (the {@code SlotMaterializer} /
 * {@code IntentHandler} SPI contracts). The E4 engine + the E2 responder cores stay byte-equivalent.
 *
 * <p><strong>Error band 4420-4429</strong> (the {@code GlobalErrorHandler} Javadoc table). 4421 (waitlist-join
 * with no contactId) is the lone minted HTTP code; the rest of the flow reuses the engine's codes /
 * advisory-logging. 4420 + 4422-4429 reserved for RescheduleFlow growth.
 *
 * <p>Beans are hand-constructed (not component-scanned) so this composes cleanly with the both-module gate
 * (the chairfill/salon/realestate/nurture lesson). The {@code @RestController} ({@link RescheduleController})
 * IS component-scanned but {@code @ConditionalOnProperty}-gated, so it is absent from the OpenAPI spec when
 * the module is off (the {@code NoShowRiskController} / {@code SwitchboardController} precedent).
 */
@AutoConfiguration(after = {FrontDeskAutoConfiguration.class, WaitlistAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@ConditionalOnBean(WaitlistClaimEngine.class)
public class RescheduleFlowAutoConfiguration {

    /** The PHI-free fill-funnel ledger writer/reader. */
    @Bean
    public RescheduleAnalyticsService rescheduleAnalyticsService(RescheduleFillLogRepository fillLogs) {
        return new RescheduleAnalyticsService(fillLogs);
    }

    /**
     * The frontdesk {@link com.kumouri.kmodigipresbe.service.waitlist.SlotMaterializer} (creates the real
     * PHI-free Appointment on a winning claim). Registered into the E4 engine's {@code List<SlotMaterializer>}
     * purely by being a bean — no engine edit; the engine dispatches to it by {@code slotType="health-appt"}.
     */
    @Bean
    public FrontDeskSlotMaterializer frontDeskSlotMaterializer(
            AppointmentRepository appointments,
            RescheduleAnalyticsService rescheduleAnalyticsService,
            DomainEventPublisher eventPublisher) {
        return new FrontDeskSlotMaterializer(appointments, rescheduleAnalyticsService, eventPublisher);
    }

    /**
     * The cancel → gap-fill freed-slot trigger (an {@code APPOINTMENT_CANCELLED} subscriber). Its
     * {@code @PostConstruct} fires the bus subscription at init. Delegates ranking/offers/SMS to the reused
     * {@link GapFillEngine}.
     */
    @Bean
    public RescheduleGapFillSubscriber rescheduleGapFillSubscriber(
            DomainEventPublisher eventPublisher,
            TenantRepository tenantRepository,
            GapFillEngine gapFillEngine,
            RescheduleAnalyticsService rescheduleAnalyticsService) {
        return new RescheduleGapFillSubscriber(eventPublisher, tenantRepository, gapFillEngine,
                rescheduleAnalyticsService);
    }

    /**
     * The inbound-YES → claim handler (an E2 {@link com.kumouri.kmodigipresbe.service.responder.IntentHandler}).
     * Auto-discovered by the responder router's {@code List<IntentHandler>} inject — registered purely by being
     * a bean (no router edit). Delegates the atomic claim to the reused {@link WaitlistClaimEngine}.
     */
    @Bean
    public RescheduleWaitlistIntentHandler rescheduleWaitlistIntentHandler(
            WaitlistClaimEngine waitlistClaimEngine,
            RescheduleAnalyticsService rescheduleAnalyticsService) {
        return new RescheduleWaitlistIntentHandler(waitlistClaimEngine, rescheduleAnalyticsService);
    }
}
