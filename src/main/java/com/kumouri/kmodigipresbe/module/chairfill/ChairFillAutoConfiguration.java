package com.kumouri.kmodigipresbe.module.chairfill;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.WorkflowRuleRepository;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.ai.ReminderCopyService;
import com.kumouri.kmodigipresbe.module.chairfill.automation.ChairFillReminderAutomation;
import com.kumouri.kmodigipresbe.module.chairfill.automation.ReminderLogRepository;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.chairfill.scoring.NoShowRiskScoringService;
import com.kumouri.kmodigipresbe.module.salonspa.SalonSpaAutoConfiguration;
import com.kumouri.kmodigipresbe.module.salonspa.repository.BookingRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.ServiceMenuRepository;
import com.kumouri.kmodigipresbe.module.salonspa.repository.StaffMemberRepository;
import com.kumouri.kmodigipresbe.module.salonspa.service.SalonBookingService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.NoShowScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.util.List;

/**
 * ChairFill — the personal-care / salon flagship module. Loaded only when
 * {@code kmosf.modules.chairfill.enabled=true} (CF-1 D3).
 *
 * <p>ChairFill rides the shipped {@link SalonSpaAutoConfiguration salon-spa} module — it reads
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.Booking}s and
 * {@link com.kumouri.kmodigipresbe.module.salonspa.model.ServiceMenu}s — so a tenant must have BOTH
 * {@code salon-spa} and {@code chairfill} in {@code Tenant.enabledModules}. The beans here are
 * {@link ConditionalOnBean}({@link SalonBookingService}.class): if salon-spa is off on this server,
 * ChairFill silently no-ops (no bean is created), exactly like
 * {@link com.kumouri.kmodigipresbe.module.homeservices.HomeServicesAutoConfiguration}'s field-service
 * gate. <strong>Blast radius zero:</strong> with the property absent/false no ChairFill bean exists,
 * and the nightly job + any seeders skip non-{@code chairfill} tenants — Mole / Home-Services /
 * lead-scoring tenants and salon tenants without ChairFill are byte-identically unaffected.
 *
 * <p>Per-PR build-out:
 * <ul>
 *   <li><strong>CF-1 (this PR):</strong> the module skeleton + the no-show risk model
 *       ({@link NoShowRiskScoringService}, the nightly Smile {@code LogisticRegression} fork of
 *       {@code LeadScoringV2Service}), stamping {@code Booking.noShowRisk} on upcoming bookings and
 *       emitting {@code BOOKING_RISK_SCORED}. No outbound comms.</li>
 *   <li><strong>CF-2:</strong> risk-tiered prevention — the {@link RiskTieredPreventionService}
 *       subscriber on {@code BOOKING_RISK_SCORED} (HIGH ⇒ require a deposit via the reused salon
 *       deposit path + an extra confirmation; LOW/MEDIUM ⇒ a single Claude-personalized reminder via
 *       {@link ReminderCopyService}), plus the owner-tunable {@link ChairFillReminderAutomation}
 *       baseline WorkflowRule seeder. TCPA-safe (opt-out tag + per-contact frequency cap) and
 *       best-effort (Claude/SMS/deposit failure degrades, never drops a booking).</li>
 *   <li>CF-3: gap-fill waitlist auto-offer (the double-YES-correct showpiece).</li>
 *   <li>CF-4: AI review-reply, salon-generalized.</li>
 *   <li>CF-5: FE surfaces (separate repo).</li>
 * </ul>
 */
@AutoConfiguration(after = SalonSpaAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.chairfill", name = "enabled")
public class ChairFillAutoConfiguration {

    public static final String MODULE_KEY = "chairfill";

    @Bean
    public ModuleDefinition chairFillModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "ChairFill", "0.1.0",
                List.of("NO_SHOW_RISK"));
    }

    /**
     * The nightly no-show-risk scorer. {@link ConditionalOnBean}({@link SalonBookingService}.class)
     * so ChairFill no-ops when the salon-spa module it depends on is not loaded on this server.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public NoShowRiskScoringService noShowRiskScoringService(
            TenantRepository tenantRepository,
            BookingRepository bookingRepository,
            ServiceMenuRepository serviceMenuRepository,
            NoShowScoringJobRepository jobRepository,
            DomainEventPublisher eventPublisher,
            @Value("${kmosf.chairfill.noshow-scoring.high-threshold:" + NoShowRisk.DEFAULT_HIGH_THRESHOLD + "}")
            double highThreshold,
            @Value("${kmosf.chairfill.noshow-scoring.medium-threshold:" + NoShowRisk.DEFAULT_MEDIUM_THRESHOLD + "}")
            double mediumThreshold) {
        return new NoShowRiskScoringService(tenantRepository, bookingRepository, serviceMenuRepository,
                jobRepository, eventPublisher, highThreshold, mediumThreshold);
    }

    // ── CF-2: risk-tiered prevention ──────────────────────────────────────────

    /**
     * The Claude-personalized reminder drafter (CF-2). A sibling of {@code GbpReplyDraftService} —
     * per-tenant Anthropic key + house-key fallback, {@link AiUsageRecorder} budget gate, WireMock-able
     * base-url. Hand-constructed so the {@code @Value}-resolved config lands on the factory params
     * (the salon-spa {@code @Bean} construction pattern; component-scan {@code @Value} would not fire).
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public ReminderCopyService chairFillReminderCopyService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.chairfill.reminder-draft-model:claude-haiku-4-5}") String draftModel,
            @Value("${kmosf.chairfill.reminder-system-prompt:}") String systemPromptOverride) {
        return new ReminderCopyService(webClientBuilder, connections, usageRecorder,
                baseUrl, houseKey, draftModel, systemPromptOverride);
    }

    /**
     * The risk-tiered prevention subscriber (CF-2). Subscribes to {@code BOOKING_RISK_SCORED}; HIGH
     * requires a deposit (reused salon path) + an extra confirmation, LOW/MEDIUM sends one
     * Claude-personalized reminder. TCPA-safe + best-effort (see {@link RiskTieredPreventionService}).
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public RiskTieredPreventionService riskTieredPreventionService(
            DomainEventPublisher eventPublisher,
            TenantRepository tenantRepository,
            BookingRepository bookingRepository,
            ContactRepository contactRepository,
            StaffMemberRepository staffMemberRepository,
            ServiceMenuRepository serviceMenuRepository,
            SalonBookingService salonBookingService,
            ReminderCopyService reminderCopyService,
            TwilioSmsService twilioSmsService,
            ReminderLogRepository reminderLogRepository,
            @Value("${kmosf.chairfill.prevention.deposit-rate:0.25}") BigDecimal depositRate,
            @Value("${kmosf.chairfill.prevention.deposit-min:20.00}") BigDecimal depositMin,
            @Value("${kmosf.chairfill.prevention.brand-tone:}") String brandTone,
            @Value("${kmosf.chairfill.prevention.max-per-contact-per-window:2}") int maxPerContactPerWindow,
            @Value("${kmosf.chairfill.prevention.window-hours:24}") long windowHours) {
        return new RiskTieredPreventionService(eventPublisher, tenantRepository, bookingRepository,
                contactRepository, staffMemberRepository, serviceMenuRepository, salonBookingService,
                reminderCopyService, twilioSmsService, reminderLogRepository, depositRate, depositMin,
                brandTone, maxPerContactPerWindow, windowHours);
    }

    /**
     * The owner-tunable baseline reminder {@link com.kumouri.kmodigipresbe.automation.WorkflowRule}
     * seeder (CF-2 / D5). Seeds a toggleable static SEND_SMS rule on {@code BOOKING_RISK_SCORED} per
     * chairfill tenant; the personalized/deposit logic stays in {@link RiskTieredPreventionService}.
     */
    @Bean
    @ConditionalOnBean(SalonBookingService.class)
    public ChairFillReminderAutomation chairFillReminderAutomation(
            TenantRepository tenantRepository,
            WorkflowRuleRepository workflowRuleRepository) {
        return new ChairFillReminderAutomation(tenantRepository, workflowRuleRepository);
    }
}
