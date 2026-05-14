package com.kumouri.kmodigipresbe.module.fieldservice;

import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.fieldservice.config.FieldServiceProperties;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.CaptureRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.service.CaptureService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.FileStorageService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.JobSiteService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.RecurrenceExpansionService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.S3FileStorageService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;

import java.util.List;

/**
 * Field-service vertical module. Loaded only when {@code kmosf.modules.field-service.enabled=true}.
 *
 * <p>Per-tenant enablement layers on top via {@link com.kumouri.kmodigipresbe.extension.TenantModuleRegistry}
 * — every handler in this module calls {@code registry.requireEnabled(MODULE_KEY)} so a
 * tenant without {@code field-service} in {@code Tenant.enabledModules} gets a clean 404.
 *
 * <p>Controllers are picked up via component scan (each is itself
 * {@code @ConditionalOnProperty}-gated for belt-and-braces), but services have NO
 * {@code @Component}/{@code @Service} annotations — they're declared as {@code @Bean}
 * methods here so the entire dependency graph is conditional on the property.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.field-service", name = "enabled")
@EnableConfigurationProperties(FieldServiceProperties.class)
public class FieldServiceAutoConfiguration {

    public static final String MODULE_KEY = "field-service";

    @Bean
    public ModuleDefinition fieldServiceModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Field Service", "0.1.0",
                List.of("JOB_SITE", "WORK_ORDER", "CAPTURE"));
    }

    @Bean
    public RecurrenceExpansionService recurrenceExpansionService() {
        return new RecurrenceExpansionService();
    }

    @Bean
    public FileStorageService fileStorageService(FieldServiceProperties props) {
        return S3FileStorageService.create(props);
    }

    @Bean
    public JobSiteService jobSiteService(JobSiteRepository jobSites, ReactiveMongoOperations mongo) {
        return new JobSiteService(jobSites, mongo);
    }

    @Bean
    public WorkOrderService workOrderService(WorkOrderRepository workOrders,
                                             ActivityRepository activities,
                                             RecurrenceExpansionService recurrence) {
        return new WorkOrderService(workOrders, activities, recurrence);
    }

    @Bean
    public CaptureService captureService(CaptureRepository captures,
                                         FileStorageService storage,
                                         FieldServiceProperties props) {
        return new CaptureService(captures, storage, props);
    }
}
