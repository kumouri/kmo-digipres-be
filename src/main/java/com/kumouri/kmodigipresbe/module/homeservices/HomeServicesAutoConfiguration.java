package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

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
 * documented here and enforced at tenant-onboarding time by the operator (or in a
 * future iteration, a tenant-onboarding validator).
 *
 * <p>Per-PR build-out within Phase 10:
 * <ul>
 *   <li><strong>10a (this PR):</strong> entities, repositories, stub controllers
 *       with module gating wired but empty handler bodies.</li>
 *   <li>10b: {@code ServiceAgreementSchedulerService} + real services for agreements
 *       and visits.</li>
 *   <li>10c: {@code EquipmentService} (CRUD + warranty scan) + {@code DispatchBoardService}.</li>
 *   <li>10d: QuickBooks Online OAuth + invoice sync.</li>
 *   <li>10e: on-the-way SMS automation + public service-request widget.</li>
 * </ul>
 *
 * <p>Service-bean registrations land in the consuming sub-PRs; this class currently
 * only contributes the {@link ModuleDefinition}.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
public class HomeServicesAutoConfiguration {

    public static final String MODULE_KEY = "home-services";

    @Bean
    public ModuleDefinition homeServicesModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Home Services", "0.1.0",
                List.of("EQUIPMENT", "SERVICE_AGREEMENT", "MAINTENANCE_VISIT"));
    }
}
