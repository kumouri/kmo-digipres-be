package com.kumouri.kmodigipresbe.module.fieldservice;

import com.kumouri.kmodigipresbe.config.FileStorageProperties;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.CaptureRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.JobSiteRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.module.fieldservice.service.CaptureService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.JobSiteService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.RecurrenceExpansionService;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.service.storage.FileStorageService;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;

import java.util.List;

/**
 * Field-service vertical module. Loaded only when {@code kmosf.modules.field-service.enabled=true}.
 *
 * <p>Phase 7 moved {@link FileStorageService} out of this module into core
 * ({@code service/storage/}) so non-field-service surfaces (quote PDFs, generic
 * attachments) can use it too. This auto-config no longer registers a
 * FileStorageService bean — it injects the core one instead.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.field-service", name = "enabled")
public class FieldServiceAutoConfiguration {

    public static final String MODULE_KEY = "field-service";

    @Bean
    public ModuleDefinition fieldServiceModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Field Service", "0.2.0",
                List.of("JOB_SITE", "WORK_ORDER", "CAPTURE"));
    }

    @Bean
    public RecurrenceExpansionService recurrenceExpansionService() {
        return new RecurrenceExpansionService();
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
                                         FileStorageProperties props) {
        return new CaptureService(captures, storage, props);
    }
}
