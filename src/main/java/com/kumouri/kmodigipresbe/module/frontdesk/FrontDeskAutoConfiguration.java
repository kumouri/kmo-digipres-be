package com.kumouri.kmodigipresbe.module.frontdesk;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.WorkflowRuleRepository;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.module.chairfill.model.NoShowRisk;
import com.kumouri.kmodigipresbe.module.frontdesk.ai.ConfirmationCopyService;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.ConfirmationLogRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.FrontDeskConfirmationService;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.FrontDeskReminderAutomation;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.RecallDetectorJob;
import com.kumouri.kmodigipresbe.module.frontdesk.automation.RecallLogRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.model.AppointmentRepository;
import com.kumouri.kmodigipresbe.module.frontdesk.scoring.FrontDeskNoShowScoringService;
import com.kumouri.kmodigipresbe.module.frontdesk.service.AppointmentService;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.FrontDeskScoringJobRepository;
import com.kumouri.kmodigipresbe.repository.SequenceRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.ai.AiUsageRecorder;
import com.kumouri.kmodigipresbe.service.sequence.SequenceCrudService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.web.reactive.function.client.WebClient;

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
 * {@code APPOINTMENT_RISK_SCORED}. The cold-start rules fallback carries a fresh practice night one.
 *
 * <p>FD-2 build-out (error band {@code 4280-4284}): outbound prevention + re-engagement, all
 * <strong>generic / PHI-free (fence F3)</strong>. (a) {@link FrontDeskConfirmationService} — a
 * {@code @PostConstruct} subscriber on {@code APPOINTMENT_RISK_SCORED} that, TCPA-gated + idempotent
 * (ledger-insert-FIRST on the {@code ConfirmationLog}), sends a risk-tiered SMS (HIGH → an extra
 * confirmation ask; LOW/MEDIUM → a light reminder), copy optionally Claude-personalized via
 * {@link ConfirmationCopyService} but never naming a procedure/provider/visit-type — the CF-2
 * {@code RiskTieredPreventionService} mirror <em>minus the deposit branch</em> (health does not deposit).
 * (b) {@link RecallDetectorJob} — a nightly recall sweep that enrolls lapsed patients into the practice's
 * recall {@code Sequence} (the shipped engine) + sends a generic recare nudge. (c)
 * {@link FrontDeskReminderAutomation} — an owner-tunable baseline {@code SEND_SMS} {@code WorkflowRule}
 * seeder. All FD-2 beans reuse error codes only (AI {@code 1200-1203}, Twilio {@code 2530-2532}).
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

    // ── FD-2: risk-tiered confirmation + recall/recare re-engagement ────────────

    /**
     * The PHI-free Claude confirmation drafter (FD-2). A sibling of the chairfill {@code ReminderCopyService}
     * — per-tenant Anthropic key + house-key fallback, {@link AiUsageRecorder} budget gate, WireMock-able
     * base-url — but its {@code ConfirmationContext} carries NO clinical/provider field and its system prompt
     * forbids inventing one (fence F3). Hand-constructed so the {@code @Value}-resolved config lands on the
     * factory params (the ChairFill/salon-spa lesson; component-scan {@code @Value} would not fire).
     */
    @Bean
    public ConfirmationCopyService frontDeskConfirmationCopyService(
            WebClient.Builder webClientBuilder,
            IntegrationConnectionRepository connections,
            AiUsageRecorder usageRecorder,
            @Value("${kmosf.ai.anthropic.base-url:https://api.anthropic.com/v1/messages}") String baseUrl,
            @Value("${kmosf.ai.anthropic.house-key:}") String houseKey,
            @Value("${kmosf.frontdesk.confirmation-draft-model:claude-haiku-4-5}") String draftModel,
            @Value("${kmosf.frontdesk.confirmation-system-prompt:}") String systemPromptOverride) {
        return new ConfirmationCopyService(webClientBuilder, connections, usageRecorder,
                baseUrl, houseKey, draftModel, systemPromptOverride);
    }

    /**
     * The risk-tiered confirmation subscriber (FD-2). Subscribes to {@code APPOINTMENT_RISK_SCORED}; HIGH
     * sends an extra confirmation ask, LOW/MEDIUM sends one light reminder — both GENERIC/PHI-free (fence
     * F3), <strong>no deposit</strong> (unlike CF-2). TCPA-safe (opt-out tag + per-contact frequency cap),
     * idempotent (ledger-insert-FIRST on (tenant, appointment)), best-effort (Claude/SMS failure degrades).
     */
    @Bean
    public FrontDeskConfirmationService frontDeskConfirmationService(
            DomainEventPublisher eventPublisher,
            TenantRepository tenantRepository,
            AppointmentRepository appointmentRepository,
            ContactRepository contactRepository,
            ConfirmationCopyService confirmationCopyService,
            TwilioSmsService twilioSmsService,
            ConfirmationLogRepository confirmationLogRepository,
            @Value("${kmosf.frontdesk.confirmation.brand-tone:}") String brandTone,
            @Value("${kmosf.frontdesk.confirmation.max-per-contact-per-window:2}") int maxPerContactPerWindow,
            @Value("${kmosf.frontdesk.confirmation.window-hours:24}") long windowHours) {
        return new FrontDeskConfirmationService(eventPublisher, tenantRepository, appointmentRepository,
                contactRepository, confirmationCopyService, twilioSmsService, confirmationLogRepository,
                brandTone, maxPerContactPerWindow, windowHours);
    }

    /**
     * The recall/recare re-engagement sweep (FD-2) — a nightly {@code @Scheduled} that finds lapsed patients
     * (last visit older than {@code recall-window-days}, no upcoming appointment) and enrolls them into the
     * practice's recall {@code Sequence} (the shipped engine) + sends a GENERIC recare nudge SMS (fence F3).
     * Idempotent per (tenant, contact, period) via the {@code RecallLog} ledger; TCPA-safe + best-effort.
     */
    @Bean
    public RecallDetectorJob frontDeskRecallDetectorJob(
            TenantRepository tenantRepository,
            AppointmentRepository appointmentRepository,
            ContactRepository contactRepository,
            RecallLogRepository recallLogRepository,
            SequenceRepository sequenceRepository,
            SequenceCrudService sequenceCrudService,
            TwilioSmsService twilioSmsService,
            @Value("${kmosf.frontdesk.recall.window-days:180}") long recallWindowDays,
            @Value("${kmosf.frontdesk.recall.sequence-name:frontdesk-recall}") String recallSequenceName,
            @Value("${kmosf.frontdesk.recall.nudge-message:"
                    + "Hi from your care team — it's been a while since your last visit. "
                    + "Reply or call us and we'll get you back on the schedule. Reply STOP to opt out.}")
            String nudgeMessage) {
        return new RecallDetectorJob(tenantRepository, appointmentRepository, contactRepository,
                recallLogRepository, sequenceRepository, sequenceCrudService, twilioSmsService,
                recallWindowDays, recallSequenceName, nudgeMessage);
    }

    /**
     * The owner-tunable baseline reminder {@link com.kumouri.kmodigipresbe.automation.WorkflowRule} seeder
     * (FD-2) — the chairfill {@code ChairFillReminderAutomation} analogue. Seeds a toggleable static
     * SEND_SMS rule on {@code APPOINTMENT_RISK_SCORED} per frontdesk tenant; the personalized/PHI-free logic
     * stays in {@link FrontDeskConfirmationService}.
     */
    @Bean
    public FrontDeskReminderAutomation frontDeskReminderAutomation(
            TenantRepository tenantRepository,
            WorkflowRuleRepository workflowRuleRepository) {
        return new FrontDeskReminderAutomation(tenantRepository, workflowRuleRepository);
    }
}
