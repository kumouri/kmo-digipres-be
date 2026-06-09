package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.util.List;

/**
 * The "Get Paid" AR / collections module (band 4600-4619) — tiered overdue-invoice dunning over the
 * existing billing core.
 *
 * <h2>Gating — DEFAULT-OFF (a money / customer-facing-comms module)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.ar", name="enabled",
 * <strong>matchIfMissing=false</strong>)} — the module's {@link ModuleDefinition} is present
 * <em>only</em> when a deployment explicitly sets {@code kmosf.modules.ar.enabled=true}. This is the
 * deliberate inverse of the default-ON vertical modules (the {@code GbpReviewPoller} /
 * {@code CoverageNudgeJob} default-OFF posture): a non-AR tenant gets no aging sweep, no SENT→OVERDUE
 * transition, no dunning — byte-identical to before this module existed. Per-tenant membership (once a
 * deployment opts the module in) is then enforced by {@code TenantModuleRegistry.requireEnabled("ar")}
 * in the AR-4 read controller.
 *
 * <p><strong>The scheduled {@link ArAgingSweepJob} is NOT registered here</strong> — it is its own
 * {@code @Component} carrying the <em>same</em> {@code kmosf.modules.ar} gate (also
 * {@code matchIfMissing=false}), so the whole module — definition and sweep — flips together with the
 * single {@code kmosf.modules.ar.enabled} flag (the {@code NurtureAutoConfiguration} +
 * {@code NurtureRunner} split, here collapsed onto one flag because the AR module is OFF by default and
 * has no administer-while-runner-off use case). The {@link DunningLogRepository} is component-scanned
 * by {@code @EnableReactiveMongoRepositories} (always present, like {@code CoverageNudgeLogRepository});
 * it is simply unused while the module is off.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.ar", name = "enabled", matchIfMissing = false)
public class ArAutoConfiguration {

    public static final String MODULE_KEY = "ar";

    @Bean
    public ModuleDefinition arModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "AR / Collections (Get Paid)", "0.1.0",
                List.of("DUNNING_LOG"));
    }
}
