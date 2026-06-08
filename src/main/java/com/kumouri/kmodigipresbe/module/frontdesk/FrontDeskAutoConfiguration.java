package com.kumouri.kmodigipresbe.module.frontdesk;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.scoring.FrontDeskNoShowScoringService;
import com.kumouri.kmodigipresbe.module.frontdesk.service.AppointmentService;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.FrontDeskScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * FrontDesk IQ — the health-practices flagship module (flagship #4). Loaded only when
 * {@code kmosf.modules.frontdesk.enabled=true} (FD-1 D3, the {@link RealEstateAutoConfiguration} template).
 *
 * <h2>The headline: PHI-free by construction</h2>
 * <p>FrontDesk IQ's pitch is "a smarter front desk that never touches the chart." The boundary is enforced
 * at the data layer (the {@code Appointment} model carries no clinical field — fence F1), not as a prompt
 * instruction. FD-1 ships the first of those fences: the PHI-free no-show-risk model.
 *
 * <p><strong>Blast radius zero.</strong> With the property absent/false no FrontDesk bean exists; the nightly
 * scorer additionally skips any tenant whose {@code enabledModules} lacks {@code "frontdesk"}, so NMM /
 * Home-Services / ChairFill / Real Estate / lead-scoring tenants are byte-identically unaffected. The module
 * rides the <strong>core CRM spine directly</strong> — no salon-spa / home-services / realestate dependency
 * (exactly like {@link RealEstateAutoConfiguration}, unlike ChairFill which rode salon-spa). It reuses the
 * chairfill {@code NoShowRisk}/{@code NoShowRiskTier} value <em>types</em> verbatim (domain-neutral records;
 * no module→module structural rule forbids it — {@code RealEstateAutoConfiguration} already imports
 * chairfill) but shares no data with CF-1's scorer. Error band: <strong>4275-4299</strong> (FD-1:
 * {@code 4275-4279}).
 *
 * <p>Beans are hand-constructed (not component-scanned) so the {@code @Value}-resolved config lands on the
 * factory params (component-scan {@code @Value} would not fire — the ChairFill/salon-spa lesson). The
 * {@code @RestController}s ({@code AppointmentController}, {@code NoShowRiskController}) ARE component-scanned
 * but {@code @ConditionalOnProperty}-gated, so they are absent from the OpenAPI spec when the module is off
 * (the {@code NoShowRiskController} precedent).
 *
 * <p>FD-1 build-out: the {@code frontdesk} module skeleton + the thin PHI-free {@code Appointment}
 * model/repo + a minimal staff appointment CRUD ({@link AppointmentService}) + the nightly
 * {@link FrontDeskNoShowScoringService} (a parallel fork of the chairfill no-show scorer, itself a fork of
 * {@code LeadScoringV2Service}) that stamps {@code Appointment.noShowRisk} on UPCOMING appointments and emits
 * {@code APPOINTMENT_RISK_SCORED}. No outbound comms (that is FD-2). The cold-start rules fallback carries a
 * fresh practice night one.
 */
@AutoConfiguration(after = RealEstateAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
public class FrontDeskAutoConfiguration {

    public static final String MODULE_KEY = "frontdesk";

    @Bean
    public ModuleDefinition frontDeskModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "FrontDesk IQ", "0.1.0",
                List.of("APPOINTMENT", "NO_SHOW_RISK"));
    }

    /** Staff CRUD for the thin {@code Appointment} (the {@code ListingService} posture). */
    @Bean
    public AppointmentService frontDeskAppointmentService(AppointmentRepository appointments) {
        return new AppointmentService(appointments);
    }

    /**
     * The nightly PHI-free no-show-risk scorer (FD-1). A parallel fork of the chairfill scorer that reads
     * only {@link AppointmentRepository} and a logistics-only feature vector (fence F1). Hand-constructed so
     * the {@code @Value}-resolved thresholds land on the factory params.
     */
    @Bean
    public FrontDeskNoShowScoringService frontDeskNoShowScoringService(
            TenantRepository tenantRepository,
            AppointmentRepository appointmentRepository,
            FrontDeskScoringJobRepository jobRepository,
            DomainEventPublisher eventPublisher,
            @Value("${kmosf.frontdesk.noshow-scoring.high-threshold:" + NoShowRisk.DEFAULT_HIGH_THRESHOLD + "}")
            double highThreshold,
            @Value("${kmosf.frontdesk.noshow-scoring.medium-threshold:" + NoShowRisk.DEFAULT_MEDIUM_THRESHOLD + "}")
            double mediumThreshold) {
        return new FrontDeskNoShowScoringService(tenantRepository, appointmentRepository, jobRepository,
                eventPublisher, highThreshold, mediumThreshold);
    }
}
