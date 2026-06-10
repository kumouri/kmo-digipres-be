package com.kumouri.kmodigipresbe.module.realestate.nurture;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.module.realestate.marketing.FairHousingLint;
import com.kumouri.kmodigipresbe.module.realestate.model.ListingMarketingDraft.FairHousingFlag;
import com.kumouri.kmodigipresbe.module.realestate.model.MarketingChannel;
import com.kumouri.kmodigipresbe.service.nurture.NurtureCopyFilter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * T1 (RE Database Goldmine) — the <strong>Fair-Housing guardrail on every nurture outbound message</strong>.
 * The RE deployment of the E1 nurture engine wires this as the {@link NurtureCopyFilter} on the shared
 * {@code NurtureMessageComposer}, so <em>every</em> RE nurture message — AI-personalized AND templated —
 * is screened before it is sent (the composer is the single chokepoint both paths funnel through).
 *
 * <h2>Reuses the shipped {@link FairHousingLint} (RE-4 Marketing Studio)</h2>
 * The deterministic FHA §3604(c) banned-term scan ({@code FairHousingLint.lint}) is the exact same screen
 * the Marketing Studio uses on generated listing copy — REUSED verbatim (that core is empty-diff). A
 * <strong>non-empty</strong> flag list means residual Fair-Housing risk (protected-class / steering /
 * familial-status / etc. language).
 *
 * <h2>Contract (the headline correctness property — T1 directive #2)</h2>
 * <ul>
 *   <li>Clean copy → passes through unchanged.</li>
 *   <li>Non-compliant copy → <strong>replaced</strong> with a per-channel vetted safe template (rendered
 *       for the contact). <strong>Never sends non-compliant copy; never drops the send silently</strong>
 *       (a safe message always goes out); the block is logged at WARN with the flagged terms (the
 *       advisory marker, error band {@code 4360}).</li>
 *   <li>Best-effort — never throws (a defensive failure falls through to the safe template).</li>
 * </ul>
 *
 * <h2>Channel mapping</h2>
 * {@link FairHousingLint} scans plain text; its banned-term list is channel-independent in substance. We
 * pass {@link MarketingChannel#EMAIL_BLAST} for EMAIL bodies and {@link MarketingChannel#MLS_REMARKS} for
 * SMS (a representative prose channel — there is no SMS-specific {@code MarketingChannel}); the channel
 * only stamps the returned {@link FairHousingFlag} for logging, it does not change which terms match.
 *
 * <p>Stateless; hand-constructed as a {@code @Bean} by {@code RealEstateNurtureAutoConfiguration} so the
 * {@code @Value}-resolved safe templates land on the constructor params.
 */
@Slf4j
public class FairHousingCopyFilter implements NurtureCopyFilter {

    /** The advisory error-band marker for a Fair-Housing safe-fallback substitution (T1, 4360-4369). */
    public static final int FAIR_HOUSING_SAFE_FALLBACK_CODE = 4360;

    /** The vertical this filter screens (the GATE-2 dispatch key) — campaigns tagged {@code "realestate"}. */
    public static final String VERTICAL = "realestate";

    private final String safeSmsTemplate;
    private final String safeEmailBodyTemplate;

    public FairHousingCopyFilter(String safeSmsTemplate, String safeEmailBodyTemplate) {
        this.safeSmsTemplate = (safeSmsTemplate == null || safeSmsTemplate.isBlank())
                ? DEFAULT_SAFE_SMS : safeSmsTemplate;
        this.safeEmailBodyTemplate = (safeEmailBodyTemplate == null || safeEmailBodyTemplate.isBlank())
                ? DEFAULT_SAFE_EMAIL_BODY : safeEmailBodyTemplate;
    }

    static final String DEFAULT_SAFE_SMS =
            "Hi {firstName}, it's your agent — just checking in. Reply YES and we'll find a time to chat "
                    + "about your real-estate goals.";
    static final String DEFAULT_SAFE_EMAIL_BODY =
            "Hi {firstName}, just checking in. Reply and we'll set up a time to talk through your "
                    + "real-estate goals.";

    @Override
    public String vertical() {
        return VERTICAL;
    }

    @Override
    public Mono<FilterResult> filter(NurtureChannel channel, String body, Contact contact) {
        return Mono.fromSupplier(() -> screen(channel, body, contact))
                // Defensive: any unexpected failure falls back to the safe template (never sends bad copy).
                .onErrorResume(e -> {
                    log.warn("Fair-Housing copy filter errored — substituting safe template (4360): {}",
                            e.toString());
                    return Mono.just(FilterResult.replaced(
                            renderSafe(channel, contact), "fair-housing: filter error"));
                });
    }

    private FilterResult screen(NurtureChannel channel, String body, Contact contact) {
        List<FairHousingFlag> flags = FairHousingLint.lint(marketingChannelFor(channel), body);
        if (flags.isEmpty()) {
            return FilterResult.passthrough(body);
        }
        String terms = flags.stream().map(FairHousingFlag::getTerm).collect(Collectors.joining(", "));
        log.warn("Fair-Housing lint flagged a {} nurture message ({} term(s): {}) — substituting the "
                + "vetted safe template (4360)", channel, flags.size(), terms);
        return FilterResult.replaced(renderSafe(channel, contact), "fair-housing: " + terms);
    }

    /** The per-channel vetted safe template, rendered for the contact (same placeholders as the composer). */
    private String renderSafe(NurtureChannel channel, Contact contact) {
        String template = channel == NurtureChannel.EMAIL ? safeEmailBodyTemplate : safeSmsTemplate;
        return render(template, contact);
    }

    /** Map the nurture channel to a representative {@link MarketingChannel} for the lint (logging only). */
    private static MarketingChannel marketingChannelFor(NurtureChannel channel) {
        return channel == NurtureChannel.EMAIL ? MarketingChannel.EMAIL_BLAST : MarketingChannel.MLS_REMARKS;
    }

    /** {@code {firstName}} / {@code {name}} substitution — mirrors the composer's null-safe render. */
    private static String render(String template, Contact contact) {
        if (template == null) {
            return "";
        }
        String first = contact != null && contact.getFirstName() != null ? contact.getFirstName() : "";
        String name = contact != null && contact.getDisplayName() != null
                ? contact.getDisplayName()
                : (first.isBlank() ? "" : first);
        return template.replace("{firstName}", first).replace("{name}", name);
    }
}
