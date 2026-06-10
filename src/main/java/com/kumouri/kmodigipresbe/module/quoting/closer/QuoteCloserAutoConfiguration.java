package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureCampaignRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import com.kumouri.kmodigipresbe.service.nurture.NurtureMessageComposer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

import java.time.Clock;
import java.time.Duration;

/**
 * T11 (Home "QuoteCloser") — the both-module auto-configuration that wires the first Wave-4 COMPOSITION
 * tool over the shipped T8 QuoteNow quoting + E1 Nurture + E3 review-request. The
 * {@code RealEstateMidnightAutoConfiguration} (T3 realestate+responder) precedent, here for
 * <strong>quoting + nurture</strong>.
 *
 * <h2>Both-modules gate (quoting AND nurture)</h2>
 * <ul>
 *   <li>{@code @ConditionalOnProperty(kmosf.modules.quoting.enabled)} on the class — the quoting gate
 *       (default-OFF, no {@code matchIfMissing}; the {@link QuotingAutoConfiguration} posture);</li>
 *   <li>{@code @ConditionalOnBean(NurtureMessageComposer.class)} — the nurture gate: the E1 nurture beans
 *       (incl. {@link NurtureMessageComposer}) exist <strong>only</strong> when
 *       {@code kmosf.modules.nurture.enabled} (matchIfMissing=true) is on (they are {@code @Bean}s in
 *       {@link NurtureAutoConfiguration}). A deployment that disables nurture has no composer bean → this
 *       whole config is absent. {@code @AutoConfiguration(after=...)} guarantees both prerequisite configs
 *       are processed first.</li>
 * </ul>
 * Net: quoting ON + nurture ON ⇒ active; either OFF ⇒ absent (a hard no-op — {@code QuoteCloserModuleGateIT}
 * proves both directions). Per-tenant membership is enforced by {@code TenantModuleRegistry.requireEnabled}
 * for both keys in the controllers. The review leg additionally relies on the E3
 * {@link ReviewRequestRepository}/{@code ReviewRequestService}, which are <strong>always present</strong>
 * (the E3 always-create / default-OFF-send split), so no extra structural gate is needed — the per-tenant
 * {@code gbp-reviews} enablement + the default-OFF {@code ReviewRequestSenderJob} are the deployment's send
 * controls (the review request is created regardless; nothing is texted unless the sender is opted in).
 *
 * <h2>What it wires — the ACTIVE composition glue (hand-constructed beans)</h2>
 * <ul>
 *   <li>{@link QuoteCloserEnrollmentJob} — the <strong>default-OFF</strong> enrollment + stop sweep
 *       ({@code @ConditionalOnProperty(kmosf.modules.quote-closer-job)} on the {@code @Bean} method —
 *       matchIfMissing=false, so it is absent in CI / any default run; the {@code CoverageNudgeJob}
 *       posture). Resolves its {@link Clock} via {@link ObjectProvider} (a test fixed-clock bean overrides
 *       it).</li>
 *   <li>{@link QuoteWonSubscriber} — the {@code QUOTE_ACCEPTED} subscriber (stop the cadence + create one
 *       review request); its {@code @PostConstruct} fires the bus subscription at init.</li>
 * </ul>
 * These are the pieces that should be <strong>inert when nurture is not deployed</strong> (no cadence to
 * enroll into / stop) — so they carry the both-module gate. The read surface
 * ({@link QuoteCloserAnalyticsService} + the controllers) is wired by the quoting-only
 * {@link QuoteCloserReadAutoConfiguration} (so the component-scanned {@link QuoteCloserController} always
 * resolves its analytics dependency when quoting is on; the controllers still enforce the both-module
 * requirement per-tenant via {@code requireEnabled("quoting")} AND {@code requireEnabled("nurture")}).
 *
 * <p><strong>Error band 4470-4479</strong> (the {@code GlobalErrorHandler} Javadoc table). 4470 config
 * not-found; 4471 invalid config (campaignId not a tenant campaign). 4472-4479 reserved.
 */
@AutoConfiguration(after = {QuotingAutoConfiguration.class, NurtureAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
@ConditionalOnBean(NurtureMessageComposer.class)
public class QuoteCloserAutoConfiguration {

    /**
     * The default-OFF enrollment + stop sweep: ages NEW quotes into the QuoteCloser cadence + exits
     * no-longer-open enrollments. {@code @ConditionalOnProperty(kmosf.modules.quote-closer-job)} on the
     * method (matchIfMissing=false) so it is absent in CI / any default run (the {@code CoverageNudgeJob}
     * default-OFF posture). Enrolling does NOT send — the default-OFF {@code NurtureRunner} is the sender.
     */
    @Bean
    @ConditionalOnProperty(prefix = "kmosf.modules.quote-closer-job", name = "enabled",
            matchIfMissing = false)
    public QuoteCloserEnrollmentJob quoteCloserEnrollmentJob(
            TenantRepository tenants,
            QuoteCloserConfigRepository configs,
            QuoteRequestRepository quotes,
            NurtureCampaignRepository campaigns,
            NurtureEnrollmentRepository enrollments,
            DomainEventPublisher events,
            ObjectProvider<Clock> clockProvider) {
        return new QuoteCloserEnrollmentJob(tenants, configs, quotes, campaigns, enrollments, events,
                clockProvider.getIfAvailable(Clock::systemUTC));
    }

    /**
     * The {@code QUOTE_ACCEPTED} subscriber: stop the contact's QuoteCloser cadence (EXITED) + create one
     * E3 review request for the won job. Its {@code @PostConstruct} fires the bus subscription at init.
     */
    @Bean
    public QuoteWonSubscriber quoteWonSubscriber(
            DomainEventPublisher events,
            QuoteCloserConfigRepository configs,
            NurtureEnrollmentRepository enrollments,
            ReviewRequestRepository reviewRequests,
            @Value("${kmosf.review-engine.request-delay:PT24H}") Duration requestDelay) {
        return new QuoteWonSubscriber(events, configs, enrollments, reviewRequests, requestDelay);
    }
}
