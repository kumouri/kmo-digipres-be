package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.module.homeservices.repository.MaintenanceVisitRepository;
import com.kumouri.kmodigipresbe.module.homeservices.repository.ServiceAgreementRepository;
import com.kumouri.kmodigipresbe.module.homeservices.service.MaintenanceVisitService;
import com.kumouri.kmodigipresbe.module.homeservices.service.ServiceAgreementSchedulerService;
import com.kumouri.kmodigipresbe.module.homeservices.service.ServiceAgreementService;
import com.kumouri.kmodigipresbe.service.scheduling.RecurringSchedule;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.List;

/**
 * Home-services vertical module. Loaded only when
 * {@code kmosf.modules.home-services.enabled=true}.
 *
 * <p>Adds {@code Equipment}, {@code ServiceAgreement}, and {@code MaintenanceVisit}
 * — Phase 10 generalises NMM's field-service module into a sellable home-services
 * vertical (HVAC, pest control, landscaping). Equipment is tied to an existing
 * {@link com.kumouri.kmodigipresbe.module.fieldservice.model.JobSite} from the
 * field-service module; {@link com.kumouri.kmodigipresbe.module.homeservices.model.MaintenanceVisit}
 * dispatches into a field-service {@link com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder}.
 *
 * <p>This module is logically dependent on the {@link FieldServiceAutoConfiguration}
 * module — enabling home-services for a tenant without enabling field-service will
 * leave maintenance visits unable to dispatch into WorkOrders. The {@link ModuleDefinition}
 * record currently has no formal {@code dependsOn} field, so the dependency is
 * documented here and enforced at tenant-onboarding time by the operator.
 *
 * <p>Per-PR build-out within Phase 10:
 * <ul>
 *   <li>10a: entities, repositories, stub controllers with module gating.</li>
 *   <li><strong>10b (this PR):</strong> {@code ServiceAgreementSchedulerService}
 *       + agreement/visit services + real controllers. Materialization of
 *       {@code MaintenanceVisit}s from the agreement's RFC 5545 RRULE (90-day
 *       window); dispatch into {@code WorkOrder}.</li>
 *   <li>10c: {@code EquipmentService} (CRUD + warranty scan) + {@code DispatchBoardService}.</li>
 *   <li>10d: QuickBooks Online OAuth + invoice sync.</li>
 *   <li>10e: on-the-way SMS automation + public service-request widget.</li>
 * </ul>
 */
@AutoConfiguration(after = FieldServiceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
public class HomeServicesAutoConfiguration {

    public static final String MODULE_KEY = "home-services";

    @Bean
    public ModuleDefinition homeServicesModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Home Services", "0.2.0",
                List.of("EQUIPMENT", "SERVICE_AGREEMENT", "MAINTENANCE_VISIT"));
    }

    @Bean
    public ServiceAgreementSchedulerService serviceAgreementSchedulerService(
            ServiceAgreementRepository agreements,
            MaintenanceVisitRepository visits,
            RecurringSchedule recurringSchedule,
            org.springframework.beans.factory.ObjectProvider<Clock> clockProvider) {
        return new ServiceAgreementSchedulerService(
                agreements, visits, recurringSchedule,
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    @Bean
    public ServiceAgreementService serviceAgreementService(
            ServiceAgreementRepository agreements,
            ServiceAgreementSchedulerService scheduler) {
        return new ServiceAgreementService(agreements, scheduler);
    }

    /**
     * Gated on {@link WorkOrderService} so the home-services module can boot
     * even when the field-service module is disabled — every endpoint except
     * {@code POST /maintenance-visits/{id}/dispatch} stays functional. The
     * controller is conditioned on this bean so its handlers disappear too
     * when field-service is off.
     */
    @Bean
    @ConditionalOnBean(WorkOrderService.class)
    public MaintenanceVisitService maintenanceVisitService(
            MaintenanceVisitRepository visits,
            WorkOrderService workOrders) {
        return new MaintenanceVisitService(visits, workOrders);
    }
}
