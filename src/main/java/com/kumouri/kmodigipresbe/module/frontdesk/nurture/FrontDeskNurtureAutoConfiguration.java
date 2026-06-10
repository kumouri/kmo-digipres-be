package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.kumouri.kmodigipresbe.module.frontdesk.FrontDeskAutoConfiguration;
import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.service.nurture.NurtureAnalyticsService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureMessageComposer;
import com.kumouri.kmodigipresbe.service.nurture.NurtureReplyService;
import com.kumouri.kmodigipresbe.service.nurture.NurtureSegmentationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T2 (Health "RevenueRevive") — deploys the shipped E1 Nurture/Cadence engine to the frontdesk (health)
 * vertical (the health twin of T1's {@code RealEstateNurtureAutoConfiguration}). This is the health
 * deployment + PHI-free guardrail + reply→rebook wiring + analytics surface; the engine itself is NOT
 * reimplemented (the segmentation / cadence runner / backoff / reply→book / analytics are all the E1
 * services, reused).
 *
 * <h2>Both-modules gate (T2 directive #4 — compose frontdesk AND nurture)</h2>
 * The health-nurture deployment needs BOTH modules. Composed via:
 * <ul>
 *   <li>{@link ConditionalOnProperty}{@code (kmosf.modules.frontdesk.enabled)} on the class — the frontdesk
 *       gate (matchIfMissing defaults to <strong>false</strong>, so this whole config is OFF unless
 *       frontdesk is explicitly enabled — the {@link FrontDeskAutoConfiguration} posture);</li>
 *   <li>{@link ConditionalOnBean}{@code (NurtureMessageComposer.class)} on the class — the nurture gate: the
 *       E1 nurture beans exist <strong>only</strong> when {@code kmosf.modules.nurture.enabled}
 *       (matchIfMissing=true) is on (they are {@code @Bean}s in {@link NurtureAutoConfiguration}). So a
 *       deployment that disables nurture ({@code kmosf.modules.nurture.enabled=false}) has no composer bean →
 *       this whole config (and its controller's dependency) is absent. {@code @ConditionalOnBean} on a class
 *       is evaluated in the auto-config phase, and the {@code @AutoConfiguration(after=...)} ordering below
 *       guarantees both prerequisite configs are processed first.</li>
 * </ul>
 * Net: frontdesk ON + nurture ON ⇒ active; either OFF ⇒ absent (a hard no-op). Per-tenant membership is then
 * enforced by {@code TenantModuleRegistry.requireEnabled} for both keys in {@code FrontDeskNurtureController}.
 *
 * <h2>What it wires (hand-constructed beans — the module {@code @Value} lesson)</h2>
 * <ul>
 *   <li>{@link HipaaCopyFilter} — the {@code NurtureCopyFilter} screening every health nurture outbound
 *       (template + AI) via the shipped FD-4 {@code HipaaReplyLint}; the vetted safe-fallback templates are
 *       {@code @Value}-resolved.</li>
 *   <li>{@link #frontDeskNurtureSmsWiring} — a side-effecting wiring bean (the
 *       {@code RealEstateNurtureSmsWiring} / {@code ConciergeInboundSmsWiring} precedent) that calls
 *       {@code nurtureMessageComposer.registerCopyFilter(filter)} at singleton init, registering the HIPAA
 *       screen for the {@code "health"} vertical (the GATE-2 vertical-scoped dispatch; additive, so a
 *       co-resident realestate deployment's Fair-Housing filter is not clobbered). Without it the composer
 *       screens no health copy (= byte-identical E1).</li>
 *   <li>{@link FrontDeskNurtureReplyHandler} — the E2 {@code IntentHandler} (reply→rebook); auto-discovered
 *       by the responder router's {@code List<IntentHandler>} purely by being a bean (no router edit).</li>
 *   <li>{@link FrontDeskNurtureService} — the thin frontdesk facade the {@code FrontDeskNurtureController}
 *       depends on (segment-and-enroll + per-segment analytics, both delegating to the E1 services).</li>
 * </ul>
 *
 * <p><strong>Error band 4370-4379</strong> (the {@code GlobalErrorHandler} Javadoc table). T2 mostly reuses
 * the E1 nurture codes (4301/4302/4303) for campaign ops + 4310 for reply-no-enrollment; the one health-local
 * marker is {@code 4370} (a PHI-free safe-fallback substitution — advisory/logged, not a thrown HTTP error,
 * since a non-compliant body is silently+safely replaced rather than rejected). {@code 4371-4379} reserved
 * for health-nurture growth.
 */
@AutoConfiguration(after = {FrontDeskAutoConfiguration.class, NurtureAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.frontdesk", name = "enabled")
@ConditionalOnBean(NurtureMessageComposer.class)
public class FrontDeskNurtureAutoConfiguration {

    /**
     * The PHI-free screen applied to every health nurture outbound message (T2 directive #2). Reuses the
     * shipped FD-4 {@code HipaaReplyLint}; substitutes a per-channel vetted safe generic template when copy
     * is flagged (patient-status confirmation or clinical vocabulary).
     */
    @Bean
    public HipaaCopyFilter frontDeskNurtureHipaaCopyFilter(
            @Value("${kmosf.frontdesk.nurture.safe-sms:}") String safeSms,
            @Value("${kmosf.frontdesk.nurture.safe-email-body:}") String safeEmailBody) {
        return new HipaaCopyFilter(safeSms, safeEmailBody);
    }

    /**
     * Registers the {@link HipaaCopyFilter} on the shared {@link NurtureMessageComposer} for the
     * {@code "health"} vertical (the {@code RealEstateNurtureSmsWiring} side-effect precedent). Returns a
     * tiny marker; the side effect is the {@code registerCopyFilter} call at singleton init. The GATE-2
     * vertical-scoped dispatch means this registration is additive — a co-resident realestate+nurture
     * deployment's Fair-Housing filter stays registered too, and each campaign is screened by its own
     * vertical's filter. Without this the composer screens no health copy (byte-identical E1) — so this is
     * the bean that makes the PHI-free guarantee real for a frontdesk+nurture deployment.
     */
    @Bean
    public FrontDeskNurtureSmsWiring frontDeskNurtureSmsWiring(
            NurtureMessageComposer nurtureMessageComposer,
            HipaaCopyFilter frontDeskNurtureHipaaCopyFilter) {
        nurtureMessageComposer.registerCopyFilter(frontDeskNurtureHipaaCopyFilter);
        return new FrontDeskNurtureSmsWiring();
    }

    /**
     * The E2 reply→rebook handler (T2 directive #3). Auto-discovered by the responder router's
     * {@code List<IntentHandler>} inject — registered purely by being a bean (no router edit).
     */
    @Bean
    public FrontDeskNurtureReplyHandler frontDeskNurtureReplyHandler(
            NurtureReplyService nurtureReplyService) {
        return new FrontDeskNurtureReplyHandler(nurtureReplyService);
    }

    /** The thin frontdesk facade over the E1 segmentation + analytics services (the controller depends on it). */
    @Bean
    public FrontDeskNurtureService frontDeskNurtureService(
            NurtureSegmentationService nurtureSegmentationService,
            NurtureAnalyticsService nurtureAnalyticsService) {
        return new FrontDeskNurtureService(nurtureSegmentationService, nurtureAnalyticsService);
    }

    /** Marker for the {@link #frontDeskNurtureSmsWiring} side-effecting wiring bean. */
    public static final class FrontDeskNurtureSmsWiring {
    }
}
