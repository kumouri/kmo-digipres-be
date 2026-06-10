package com.kumouri.kmodigipresbe.module.realestate.nurture;

import com.kumouri.kmodigipresbe.module.nurture.NurtureAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
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
 * T1 (RE Database Goldmine) — deploys the shipped E1 Nurture/Cadence engine to real estate. This is the
 * RE deployment + Fair-Housing guardrail + reply→book wiring + RE analytics surface; the engine itself is
 * NOT reimplemented (the segmentation / cadence runner / backoff / reply→book / analytics are all the E1
 * services, reused).
 *
 * <h2>Both-modules gate (T1 directive #5 — compose realestate AND nurture)</h2>
 * The RE-nurture deployment needs BOTH modules. Composed via:
 * <ul>
 *   <li>{@link ConditionalOnProperty}{@code (kmosf.modules.realestate.enabled)} on the class — the
 *       realestate gate (matchIfMissing defaults to <strong>false</strong>, so this whole config is
 *       OFF unless realestate is explicitly enabled — the {@link RealEstateAutoConfiguration} posture);</li>
 *   <li>{@link ConditionalOnBean}{@code (NurtureMessageComposer.class)} on the class — the nurture gate:
 *       the E1 nurture beans exist <strong>only</strong> when {@code kmosf.modules.nurture.enabled}
 *       (matchIfMissing=true) is on (they are {@code @Bean}s in {@link NurtureAutoConfiguration}). So a
 *       deployment that disables nurture ({@code kmosf.modules.nurture.enabled=false}) has no composer
 *       bean → this whole config (and its controller's dependency) is absent. {@code @ConditionalOnBean}
 *       on a class is evaluated in the auto-config phase, and the {@code @AutoConfiguration(after=...)}
 *       ordering below guarantees both prerequisite configs are processed first.</li>
 * </ul>
 * Net: realestate ON + nurture ON ⇒ active; either OFF ⇒ absent (a hard no-op — the
 * {@code RealEstateNurtureModuleGateIT} proves both directions). Per-tenant membership is then enforced by
 * {@code TenantModuleRegistry.requireEnabled} for both keys in {@code RealEstateNurtureController}.
 *
 * <h2>What it wires (hand-constructed beans — the realestate-module {@code @Value} lesson)</h2>
 * <ul>
 *   <li>{@link FairHousingCopyFilter} — the {@code NurtureCopyFilter} screening every RE nurture outbound
 *       (template + AI) via the shipped {@code FairHousingLint}; the vetted safe-fallback templates are
 *       {@code @Value}-resolved.</li>
 *   <li>{@link #realEstateNurtureSmsWiring} — a side-effecting wiring bean (the
 *       {@code ConciergeInboundSmsWiring} / {@code ResponderInboundSmsWiring} precedent) that calls
 *       {@code nurtureMessageComposer.registerCopyFilter(filter)} at singleton init, registering the
 *       Fair-Housing screen for the {@code "realestate"} vertical (the GATE-2 vertical-scoped dispatch;
 *       additive, so a co-resident health deployment's HIPAA filter is not clobbered). Without it the
 *       composer screens no RE copy (= byte-identical E1).</li>
 *   <li>{@link RealEstateNurtureReplyHandler} — the E2 {@code IntentHandler} (reply→book); auto-discovered
 *       by the responder router's {@code List<IntentHandler>} purely by being a bean (no router edit).</li>
 *   <li>{@link RealEstateNurtureService} — the thin RE facade the {@code RealEstateNurtureController}
 *       depends on (segment-and-enroll + per-segment analytics, both delegating to the E1 services).</li>
 * </ul>
 *
 * <p><strong>Error band 4360-4369</strong> (the {@code GlobalErrorHandler} Javadoc table). T1 mostly
 * reuses the E1 nurture codes (4301/4302/4303) for campaign ops + 4310 for reply-no-enrollment; the one
 * RE-local marker is {@code 4360} (a Fair-Housing safe-fallback substitution — advisory/logged, not a
 * thrown HTTP error, since a non-compliant body is silently+safely replaced rather than rejected).
 * {@code 4361-4369} reserved for RE-nurture growth.
 */
@AutoConfiguration(after = {RealEstateAutoConfiguration.class, NurtureAutoConfiguration.class})
@ConditionalOnProperty(prefix = "kmosf.modules.realestate", name = "enabled")
@ConditionalOnBean(NurtureMessageComposer.class)
public class RealEstateNurtureAutoConfiguration {

    /**
     * The Fair-Housing screen applied to every RE nurture outbound message (T1 directive #2). Reuses the
     * shipped {@code FairHousingLint}; substitutes a per-channel vetted safe template when copy is flagged.
     */
    @Bean
    public FairHousingCopyFilter realEstateNurtureFairHousingCopyFilter(
            @Value("${kmosf.realestate.nurture.safe-sms:}") String safeSms,
            @Value("${kmosf.realestate.nurture.safe-email-body:}") String safeEmailBody) {
        return new FairHousingCopyFilter(safeSms, safeEmailBody);
    }

    /**
     * Registers the {@link FairHousingCopyFilter} on the shared {@link NurtureMessageComposer} for the
     * {@code "realestate"} vertical (the {@code ConciergeInboundSmsWiring} side-effect precedent). Returns a
     * tiny marker; the side effect is the {@code registerCopyFilter} call at singleton init. The GATE-2
     * vertical-scoped dispatch means this registration is additive — a co-resident frontdesk+nurture
     * deployment's HIPAA filter stays registered too, and each campaign is screened by its own vertical's
     * filter. Without this the composer screens no RE copy (byte-identical E1) — so this is the bean that
     * makes the Fair-Housing guarantee real for a realestate+nurture deployment.
     */
    @Bean
    public RealEstateNurtureSmsWiring realEstateNurtureSmsWiring(
            NurtureMessageComposer nurtureMessageComposer,
            FairHousingCopyFilter realEstateNurtureFairHousingCopyFilter) {
        nurtureMessageComposer.registerCopyFilter(realEstateNurtureFairHousingCopyFilter);
        return new RealEstateNurtureSmsWiring();
    }

    /**
     * The E2 reply→book handler (T1 directive #4). Auto-discovered by the responder router's
     * {@code List<IntentHandler>} inject — registered purely by being a bean (no router edit).
     */
    @Bean
    public RealEstateNurtureReplyHandler realEstateNurtureReplyHandler(
            NurtureReplyService nurtureReplyService) {
        return new RealEstateNurtureReplyHandler(nurtureReplyService);
    }

    /** The thin RE facade over the E1 segmentation + analytics services (the RE controller depends on it). */
    @Bean
    public RealEstateNurtureService realEstateNurtureService(
            NurtureSegmentationService nurtureSegmentationService,
            NurtureAnalyticsService nurtureAnalyticsService) {
        return new RealEstateNurtureService(nurtureSegmentationService, nurtureAnalyticsService);
    }

    /** Marker for the {@link #realEstateNurtureSmsWiring} side-effecting wiring bean. */
    public static final class RealEstateNurtureSmsWiring {
    }
}
