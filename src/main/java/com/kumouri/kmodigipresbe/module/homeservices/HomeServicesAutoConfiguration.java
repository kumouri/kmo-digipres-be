package com.kumouri.kmodigipresbe.module.homeservices;

import com.kumouri.kmodigipresbe.audit.AuditEventWriter;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import com.kumouri.kmodigipresbe.module.homeservices.repository.EquipmentRepository;
import com.kumouri.kmodigipresbe.module.homeservices.service.DispatchBoardService;
import com.kumouri.kmodigipresbe.module.homeservices.service.EquipmentService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;

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
 *   <li>10a (shipped): entities, repositories, stub controllers with module gating
 *       wired but empty handler bodies.</li>
 *   <li>10b: {@code ServiceAgreementSchedulerService} + real services for agreements
 *       and visits.</li>
 *   <li><strong>10c (this PR):</strong> {@link EquipmentService} (CRUD + warranty
 *       scan emitting {@code EQUIPMENT_WARRANTY_EXPIRING}) and
 *       {@link DispatchBoardService}; controllers wired through.</li>
 *   <li>10d: QuickBooks Online OAuth + invoice sync.</li>
 *   <li>10e: on-the-way SMS automation + public service-request widget.</li>
 * </ul>
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

    @Bean
    public EquipmentService equipmentService(EquipmentRepository equipment,
                                             ReactiveMongoOperations mongo,
                                             AuditEventWriter auditor,
                                             DomainEventPublisher events) {
        return new EquipmentService(equipment, mongo, auditor, events);
    }

    @Bean
    public DispatchBoardService dispatchBoardService(ReactiveMongoOperations mongo) {
        return new DispatchBoardService(mongo);
    }
}
