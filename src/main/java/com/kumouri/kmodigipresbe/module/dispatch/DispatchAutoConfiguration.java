package com.kumouri.kmodigipresbe.module.dispatch;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchAnalyticsService;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchOptimizerService;
import com.kumouri.kmodigipresbe.module.dispatch.service.DispatchPlanService;
import com.kumouri.kmodigipresbe.module.fieldservice.FieldServiceAutoConfiguration;
import com.kumouri.kmodigipresbe.module.fieldservice.service.WorkOrderService;
import com.kumouri.kmodigipresbe.repository.UserRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoOperations;

import java.util.List;

/**
 * T14 (Home "DispatchIQ") — an <strong>intelligent dispatch optimizer</strong> that proposes the best-fit
 * technician for each open home-services work order (skill + availability + location/priority) on top of
 * the EXISTING manual dispatch board, then applies the dispatcher-reviewed assignments via the unchanged
 * {@code WorkOrderService.update} path. Loaded only when {@code kmosf.modules.dispatch.enabled=true} (the
 * {@code TechCopilotAutoConfiguration} module template), <strong>default OFF</strong> (no
 * {@code matchIfMissing}). Error band: <strong>4520-4559</strong>.
 *
 * <p><strong>The final Wave-2 flagship AI tool (T1-T14).</strong>
 *
 * <p><strong>Blast radius zero.</strong> With the property absent/false no DispatchIQ bean exists, the
 * controller is absent from the OpenAPI spec, and the manual dispatch board / every other tenant is
 * byte-identically unaffected. The module rides the shipped field-service WorkOrder spine + the manual
 * dispatch board directly: it reuses {@link WorkOrderService} (the assignment-apply path), the
 * {@code work_orders}/{@code job_sites} collections (the board's read shape), and
 * {@link UserRepository#findAllByTenantIdAndPortal} (the Phase-J tech directory) — all empty-diff. The
 * net-new is the deterministic {@link DispatchOptimizerService} + the {@link DispatchPlanService}
 * orchestrator + {@link DispatchAnalyticsService}.
 *
 * <p>Beans are hand-constructed (not component-scanned) so they land deterministically (the RE/ChairFill
 * lesson). All beans are additionally gated {@code @ConditionalOnBean(WorkOrderService.class)} — the
 * field-service module supplies it; DispatchIQ optimizes + applies work-order assignments, so it requires
 * that spine. The {@code @RestController} ({@code DispatchController}) is component-scanned but
 * {@code @ConditionalOnProperty}-gated, so it is absent from the OpenAPI spec when the module is off.
 */
@AutoConfiguration(after = FieldServiceAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.dispatch", name = "enabled")
public class DispatchAutoConfiguration {

    public static final String MODULE_KEY = "dispatch";

    @Bean
    public ModuleDefinition dispatchModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "DispatchIQ", "0.1.0",
                List.of("WORK_ORDER"));
    }

    @Bean
    public DispatchOptimizerService dispatchOptimizerService() {
        return new DispatchOptimizerService();
    }

    @Bean
    @ConditionalOnBean(WorkOrderService.class)
    public DispatchPlanService dispatchPlanService(
            DispatchOptimizerService optimizer,
            WorkOrderService workOrders,
            UserRepository users,
            ReactiveMongoOperations mongo,
            DomainEventPublisher events) {
        return new DispatchPlanService(optimizer, workOrders, users, mongo, events);
    }

    @Bean
    @ConditionalOnBean(DispatchPlanService.class)
    public DispatchAnalyticsService dispatchAnalyticsService(DispatchPlanService planService) {
        return new DispatchAnalyticsService(planService);
    }
}
