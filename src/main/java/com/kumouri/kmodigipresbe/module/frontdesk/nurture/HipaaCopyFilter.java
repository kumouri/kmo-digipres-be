package com.kumouri.kmodigipresbe.module.frontdesk.nurture;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.module.frontdesk.reviews.HipaaReplyLint;
import com.kumouri.kmodigipresbe.module.frontdesk.reviews.HipaaReplyLint.HipaaFlag;
import com.kumouri.kmodigipresbe.service.nurture.NurtureCopyFilter;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

/**
 * T2 (Health "RevenueRevive") — the <strong>PHI-free guardrail on every nurture outbound message</strong>,
 * the health analogue of T1's {@code FairHousingCopyFilter}. The frontdesk deployment of the E1 nurture
 * engine wires this as the {@link NurtureCopyFilter} on the shared {@code NurtureMessageComposer}, so
 * <em>every</em> health nurture message — AI-personalized AND templated — is screened before it is sent
 * (the composer is the single chokepoint both paths funnel through).
 *
 * <h2>Reuses the shipped {@link HipaaReplyLint} (FD-4 HIPAA-safe review-reply)</h2>
 * The deterministic patient-status / clinical-vocabulary scan ({@link HipaaReplyLint#lint(String)}) is the
 * exact same screen the FrontDesk IQ review-reply uses on a drafted public reply — REUSED verbatim (that core
 * is empty-diff). A <strong>non-empty</strong> flag list means residual PHI-disclosure risk: a phrase that
 * confirms the recipient is/was a patient ("thank you for being our patient", "your procedure") or names a
 * procedure / treatment / diagnosis / medication ("crown", "prescription", "diagnosis"). A HIPAA-safe
 * reactivation text must never do either — it is a generic "we miss you, let's get you back on the schedule"
 * nudge, never a confirmation of care.
 *
 * <h2>Contract (the headline correctness property — T2 directive #2)</h2>
 * <ul>
 *   <li>Clean copy → passes through unchanged.</li>
 *   <li>Non-compliant (PHI-ish) copy → <strong>replaced</strong> with a per-channel vetted safe generic
 *       template (rendered for the contact). <strong>Never sends PHI-ish copy; never drops the send
 *       silently</strong> (a safe message always goes out); the block is logged at WARN with the flagged
 *       terms (the advisory marker, error band {@code 4370}).</li>
 *   <li>Best-effort — never throws (a defensive failure falls through to the safe template).</li>
 * </ul>
 *
 * <h2>Channel</h2>
 * {@link HipaaReplyLint} scans plain text and is channel-independent (it takes only the draft). The channel
 * here only selects which vetted safe template to substitute (the SMS vs the email body) when a replacement
 * is needed; it never changes which terms match.
 *
 * <p>Stateless; hand-constructed as a {@code @Bean} by {@code FrontDeskNurtureAutoConfiguration} so the
 * {@code @Value}-resolved safe templates land on the constructor params.
 */
@Slf4j
public class HipaaCopyFilter implements NurtureCopyFilter {

    /** The advisory error-band marker for a PHI-free safe-fallback substitution (T2, 4370-4379). */
    public static final int PHI_SAFE_FALLBACK_CODE = 4370;

    private final String safeSmsTemplate;
    private final String safeEmailBodyTemplate;

    public HipaaCopyFilter(String safeSmsTemplate, String safeEmailBodyTemplate) {
        this.safeSmsTemplate = (safeSmsTemplate == null || safeSmsTemplate.isBlank())
                ? DEFAULT_SAFE_SMS : safeSmsTemplate;
        this.safeEmailBodyTemplate = (safeEmailBodyTemplate == null || safeEmailBodyTemplate.isBlank())
                ? DEFAULT_SAFE_EMAIL_BODY : safeEmailBodyTemplate;
    }

    static final String DEFAULT_SAFE_SMS =
            "Hi {firstName}, it's your care team — it's been a while since we saw you. Reply YES and we'll "
                    + "get you back on the schedule. Reply STOP to opt out.";
    static final String DEFAULT_SAFE_EMAIL_BODY =
            "Hi {firstName}, it's been a while since your last visit. Reply and we'll find a time to get you "
                    + "back on the schedule.";

    @Override
    public Mono<FilterResult> filter(NurtureChannel channel, String body, Contact contact) {
        return Mono.fromSupplier(() -> screen(channel, body, contact))
                // Defensive: any unexpected failure falls back to the safe template (never sends PHI-ish copy).
                .onErrorResume(e -> {
                    log.warn("HIPAA copy filter errored — substituting safe template (4370): {}",
                            e.toString());
                    return Mono.just(FilterResult.replaced(
                            renderSafe(channel, contact), "phi: filter error"));
                });
    }

    private FilterResult screen(NurtureChannel channel, String body, Contact contact) {
        List<HipaaFlag> flags = HipaaReplyLint.lint(body);
        if (flags.isEmpty()) {
            return FilterResult.passthrough(body);
        }
        String terms = flags.stream().map(HipaaFlag::term).collect(Collectors.joining(", "));
        log.warn("HIPAA lint flagged a {} nurture message ({} term(s): {}) — substituting the vetted safe "
                + "generic template (4370)", channel, flags.size(), terms);
        return FilterResult.replaced(renderSafe(channel, contact), "phi: " + terms);
    }

    /** The per-channel vetted safe template, rendered for the contact (same placeholders as the composer). */
    private String renderSafe(NurtureChannel channel, Contact contact) {
        String template = channel == NurtureChannel.EMAIL ? safeEmailBodyTemplate : safeSmsTemplate;
        return render(template, contact);
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
