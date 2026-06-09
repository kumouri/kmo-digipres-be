package com.kumouri.kmodigipresbe.module.waitlist;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.extension.ModuleAutoConfigurationSupport;
import com.kumouri.kmodigipresbe.extension.ModuleDefinition;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineEntryRepository;
import com.kumouri.kmodigipresbe.repository.waitlist.WaitlistEngineOfferRepository;
import com.kumouri.kmodigipresbe.service.waitlist.GapFillEngine;
import com.kumouri.kmodigipresbe.service.waitlist.NoOpSlotMaterializer;
import com.kumouri.kmodigipresbe.service.waitlist.SlotMaterializer;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistClaimEngine;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistOfferExpiryService;
import com.kumouri.kmodigipresbe.service.waitlist.WaitlistRankingService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

import java.util.List;

/**
 * E4 — the vertical-agnostic Gap-Fill Waitlist engine module. Loaded by default
 * ({@code @ConditionalOnProperty(prefix="kmosf.modules.waitlist", name="enabled", matchIfMissing=true)},
 * the {@code NurtureAutoConfiguration} precedent); absent only when a deployment explicitly sets
 * {@code kmosf.modules.waitlist.enabled=false}. Per-tenant membership is then enforced by
 * {@code TenantModuleRegistry.requireEnabled("waitlist")} in {@code WaitlistEngineController}.
 *
 * <h2>Vertical-agnostic by construction (design directive #2)</h2>
 * The engine ({@link GapFillEngine} / {@link WaitlistClaimEngine} / {@link WaitlistRankingService} /
 * {@link WaitlistOfferExpiryService}) has <strong>no salon Booking / health Appointment coupling</strong>.
 * On a freed slot a consumer calls {@code gapFill(tenantId, slot)}; the first YES claims it atomically and
 * the real domain-record creation is delegated to a consumer-contributed {@link SlotMaterializer} bean
 * (the E2 {@code IntentHandler} precedent — {@code WaitlistClaimEngine} auto-discovers every materializer
 * via the {@code List<SlotMaterializer>} inject and dispatches by {@code slotType}). The built-in
 * {@link NoOpSlotMaterializer} is the fallback so the engine is functional with zero consumers. So a later
 * tool (the Health "RescheduleFlow" T7) deploys PHI-free waitlist gap-fill purely by enabling the module +
 * contributing a {@code SlotMaterializer}.
 *
 * <h2>Dormant by default (design directive #3)</h2>
 * There is <strong>no event subscriber and no {@code @Scheduled} live job</strong> — the engine does
 * nothing until a consumer calls {@code gapFill(...)} / {@code claim(...)} / {@code sweepOnce()}. So a
 * default server that has the module loaded but no consumer wired sends ZERO SMS. Beans are
 * hand-constructed (not component-scanned) so the {@code @Value}-resolved config lands on the factory
 * params (the chairfill / salon / nurture lesson).
 *
 * <h2>No live external (design directive #7)</h2>
 * The only outbound is {@code TwilioSmsService} (mocked in every IT — its base URL is not config-driven);
 * the engine has NO AI dependency (deterministic template copy), so there is no Anthropic call at all.
 */
@AutoConfiguration
@ConditionalOnProperty(prefix = "kmosf.modules.waitlist", name = "enabled", matchIfMissing = true)
public class WaitlistAutoConfiguration {

    public static final String MODULE_KEY = "waitlist";

    @Bean
    public ModuleDefinition waitlistModuleDefinition() {
        return ModuleAutoConfigurationSupport.module(
                MODULE_KEY, "Gap-Fill Waitlist Engine", "0.1.0",
                List.of("WAITLIST_ENTRY", "WAITLIST_OFFER"));
    }

    /** The pure, deterministic inverted-show-risk ranking service (no vertical coupling). */
    @Bean
    public WaitlistRankingService waitlistRankingService() {
        return new WaitlistRankingService();
    }

    /**
     * The built-in fallback {@link SlotMaterializer} (the E2 default-handoff precedent). NOT
     * {@code @ConditionalOnMissingBean} — consumers ADD materializers as ADDITIONAL {@code SlotMaterializer}
     * beans; the engine auto-discovers all of them and uses this only as the fallback.
     */
    @Bean
    public NoOpSlotMaterializer waitlistNoOpSlotMaterializer() {
        return new NoOpSlotMaterializer();
    }

    /**
     * The consumer-triggered gap-fill orchestrator: rank → top-N time-boxed offers → SMS. Hand-constructed
     * so the {@code @Value}-resolved config lands on the factory params.
     */
    @Bean
    public GapFillEngine waitlistGapFillEngine(
            DomainEventPublisher eventPublisher,
            TenantRepository tenantRepository,
            WaitlistEngineEntryRepository entryRepository,
            WaitlistEngineOfferRepository offerRepository,
            ContactRepository contactRepository,
            WaitlistRankingService waitlistRankingService,
            TwilioSmsService twilioSmsService,
            @Value("${kmosf.waitlist.max-offers:3}") int maxOffers,
            @Value("${kmosf.waitlist.offer-ttl-minutes:10}") long offerTtlMinutes) {
        return new GapFillEngine(eventPublisher, tenantRepository, entryRepository, offerRepository,
                contactRepository, waitlistRankingService, twilioSmsService, maxOffers, offerTtlMinutes);
    }

    /**
     * The double-YES-correct atomic slot claim: the slot-level {@code findAndModify} via
     * {@link ReactiveMongoTemplate}, winner → the matching {@link SlotMaterializer}, loser → an apology.
     * Auto-discovers every {@code SlotMaterializer} bean via the {@code List<SlotMaterializer>} inject (so a
     * later vertical materializer is registered WITHOUT touching the engine) + the no-op fallback.
     * Confirmation / apology SMS bodies configurable.
     */
    @Bean
    public WaitlistClaimEngine waitlistClaimEngine(
            ReactiveMongoTemplate mongoTemplate,
            WaitlistEngineOfferRepository offerRepository,
            WaitlistEngineEntryRepository entryRepository,
            TwilioSmsService twilioSmsService,
            DomainEventPublisher eventPublisher,
            List<SlotMaterializer> materializers,
            NoOpSlotMaterializer noOpSlotMaterializer,
            @Value("${kmosf.waitlist.confirmation-sms:You're booked! See you soon.}")
            String confirmationTemplate,
            @Value("${kmosf.waitlist.apology-sms:Sorry — that slot was just taken. "
                    + "You're still first in line for the next opening!}") String apologyTemplate) {
        return new WaitlistClaimEngine(mongoTemplate, offerRepository, entryRepository, twilioSmsService,
                eventPublisher, materializers, noOpSlotMaterializer, confirmationTemplate, apologyTemplate);
    }

    /**
     * The stale-offer expiry sweeper — {@code sweepOnce()} ledger hygiene only, NO {@code @Scheduled} live
     * job by default (design directive #3 — the engine is consumer-triggered + dormant; a consumer / ops
     * may call {@code sweepOnce()} from its own scheduler).
     */
    @Bean
    public WaitlistOfferExpiryService waitlistOfferExpiryService(
            TenantRepository tenantRepository,
            WaitlistEngineOfferRepository offerRepository) {
        return new WaitlistOfferExpiryService(tenantRepository, offerRepository);
    }
}
