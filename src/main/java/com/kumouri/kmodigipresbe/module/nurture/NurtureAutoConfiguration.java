package com.kumouri.kmodigipresbe.module.nurture;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.util.List;

/**
 * The Nurture / Cadence Engine module (E1 — the keystone shared reactivation engine).
 *
 * <h2>Gating (the design-directive #2 module gate)</h2>
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.nurture", name="enabled", matchIfMissing=true)}
 * — the module (its {@link ModuleDefinition} + the segmentation/reply/analytics services + the admin
 * controller) is present by default and absent only when a deployment explicitly sets
 * {@code kmosf.modules.nurture.enabled=false}. Per-tenant membership is then enforced by
 * {@code TenantModuleRegistry.requireEnabled("nurture")} in the controller (1130/1132).
 *
 * <p><strong>The scheduled {@code NurtureRunner} is NOT registered here</strong> — it is its own
 * {@code @Component} gated {@code kmosf.modules.nurture-runner} (matchIfMissing=<strong>false</strong>,
 * DEFAULT-OFF) so the engine can be administered (campaigns created, segmentation run, analytics read)
 * without ever firing a live send in CI / any default run (the GBP poller/admin split precedent). The
 * services are wired as {@code @Bean}s here (the chairfill/salon module precedent) rather than
 * component-scanned, so they exist only when the module is enabled.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.nurture", name = "enabled", matchIfMissing = true)
public class NurtureAutoConfiguration {

    public static final String MODULE_KEY = "nurture";

    @Bean
    public ModuleDefinition nurtureModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Nurture / Cadence Engine", "0.1.0",
                List.of("NURTURE_CAMPAIGN", "NURTURE_ENROLLMENT"));
    }

    @Bean
    public NurtureSegmentationService nurtureSegmentationService(
            NurtureCampaignRepository campaigns,
            NurtureEnrollmentRepository enrollments,
            ContactRepository contacts,
            ActivityRepository activities,
            DealRepository deals,
            DomainEventPublisher events,
            ObjectProvider<Clock> clockProvider) {
        return new NurtureSegmentationService(
                campaigns, enrollments, contacts, activities, deals, events, clockProvider);
    }
}
