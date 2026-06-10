package com.kumouri.kmodigipresbe.module.quoting.closer;

import com.kumouri.kmodigipresbe.module.quoting.QuotingAutoConfiguration;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.repository.gbp.ReviewRequestRepository;
import com.kumouri.kmodigipresbe.repository.nurture.NurtureEnrollmentRepository;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * T11 (Home "QuoteCloser") — the <strong>quoting-only</strong> read-surface wiring (the
 * {@link QuoteCloserAnalyticsService}). Separate from the both-module {@link QuoteCloserAutoConfiguration}
 * (which wires the ACTIVE composition glue — the enrollment job + the {@code QUOTE_ACCEPTED} subscriber)
 * because the read surface is exposed by component-scanned controllers gated on quoting ALONE
 * ({@link QuoteCloserController} / {@link QuoteCloserConfigController}, the {@code QuoteInboxController}
 * precedent): the {@link QuoteCloserController}'s {@link QuoteCloserAnalyticsService} dependency must be
 * present whenever quoting is on, or the context fails to load when {@code nurture} is off (a
 * component-scanned controller cannot reliably {@code @ConditionalOnBean(NurtureMessageComposer)}, since
 * component scan runs before auto-configuration — the Spring ordering trap).
 *
 * <p>The analytics service has no hard dependency on a nurture-module bean (it only reads the unconditional
 * Spring Data repos), so gating it on quoting alone is sound. The <strong>both-module requirement is still
 * enforced at request time</strong> by the controllers' {@code requireEnabled("quoting")} AND
 * {@code requireEnabled("nurture")} guard (1130/1132) — so a quoting-only tenant gets a clean 1132 on the
 * analytics endpoint rather than a context-load failure. {@code @AutoConfiguration(after=...)} keeps it
 * ordered after the quoting module config.
 */
@AutoConfiguration(after = QuotingAutoConfiguration.class)
@ConditionalOnProperty(prefix = "kmosf.modules.quoting", name = "enabled")
public class QuoteCloserReadAutoConfiguration {

    /** The abandonment + recovery funnel read service (reads repos only; quoting-gated). */
    @Bean
    public QuoteCloserAnalyticsService quoteCloserAnalyticsService(
            QuoteCloserConfigRepository configs,
            QuoteRequestRepository quotes,
            NurtureEnrollmentRepository enrollments,
            ReviewRequestRepository reviewRequests) {
        return new QuoteCloserAnalyticsService(configs, quotes, enrollments, reviewRequests);
    }
}
