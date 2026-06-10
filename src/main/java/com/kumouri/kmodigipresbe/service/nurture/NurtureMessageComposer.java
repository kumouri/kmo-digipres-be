package com.kumouri.kmodigipresbe.service.nurture;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.service.ai.AiAssistService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Renders + (best-effort) AI-personalizes a single nurture cadence touch (E1 — Nurture / Cadence
 * Engine). Stateless.
 *
 * <h2>Two stages</h2>
 * <ol>
 *   <li><strong>Template render</strong> (deterministic): substitute {@code {firstName}} / {@code {name}}
 *       in the step's channel template from the contact. No new template engine — the baseline that
 *       always produces a sendable message.</li>
 *   <li><strong>AI personalize</strong> (best-effort, opt-in per step): if {@code step.aiPersonalize},
 *       rewrite the rendered copy via the reused {@link AiAssistService#ask} seam (per-tenant key +
 *       budget gate + configurable base-url — empty-diff). A {@code 1200} budget / {@code 1202} upstream
 *       / {@code 1203} missing-key is swallowed ({@code onErrorResume → templated}) so a failure
 *       <strong>degrades to the rendered template, never drops the send</strong> (the plan §8 "AI is
 *       triage, not truth" rule). The body field reflects which was used via {@code aiApplied}.</li>
 * </ol>
 *
 * <h2>Optional post-compose copy filter — VERTICAL-SCOPED (strictly additive; the GATE-2 fix)</h2>
 * After the two stages above, the final body is piped through the {@link NurtureCopyFilter} whose
 * {@linkplain NurtureCopyFilter#vertical() vertical} matches the composing campaign's vertical — selected
 * from a <strong>registry</strong> of filters ({@link #registerCopyFilter}, additive, never last-wins).
 * This is the single chokepoint a vertical deployment uses to vet/sanitize <em>every</em> outbound message
 * (the Real-Estate Fair-Housing screen, the Health HIPAA screen) — template AND AI-rewritten copy both
 * funnel through here. Dispatching by the campaign's vertical (rather than a single last-wins field) is the
 * keystone correctness fix: a process that enables BOTH realestate+nurture AND frontdesk+nurture now
 * screens each vertical's messages with its own filter — the second registration no longer clobbers the
 * first (the prior bug, worst case a health message shipping without the HIPAA screen).
 * <strong>When no filter matches the campaign's vertical — no filter registered (the default), or a
 * null/legacy untagged campaign — the output is byte-identical to pre-T1</strong>, so the shipped E1
 * nurture behavior + ITs are unaffected. The filter is best-effort (an error degrades to the unfiltered
 * body) and never drops the send.
 */
@Slf4j
@RequiredArgsConstructor
public class NurtureMessageComposer {

    private static final String AI_SYSTEM_PROMPT =
            "You rewrite a short reactivation message for a small local business in a warm, concise, "
                    + "professional voice. Keep it truthful and faithful to the original intent; do not "
                    + "invent offers, prices, names, or facts. Return ONLY the rewritten message text — "
                    + "no preamble, no quotes, no explanation. Keep an SMS under ~320 characters.";

    private final AiAssistService aiAssistService;

    /**
     * The registry of post-compose copy filters (the GATE-2 vertical-scoping fix). Empty by default —
     * each filter is contributed via {@link #registerCopyFilter} by a vertical module's side-effecting
     * wiring bean (the {@code conciergeRouter}/{@code intentRouter} setter precedent), so when empty the
     * composer is byte-identical to pre-T1. Hand-constructed bean (not component-scanned), so this is
     * populated via a register method, not field-{@code @Autowired}. {@link CopyOnWriteArrayList} because
     * registration happens at singleton init (a handful of writes) and {@link #compose} reads it on the
     * send path — lock-free reads, no concurrent-modification risk.
     */
    private final List<NurtureCopyFilter> copyFilters = new CopyOnWriteArrayList<>();

    /**
     * Register a vertical's post-compose copy filter (the GATE-2 fix — additive, NOT last-wins). Invoked by
     * a vertical module's side-effecting wiring bean so that vertical's screen applies to its own nurture
     * outbound; each vertical registers its own filter, so enabling two verticals in one process keeps both
     * screens live (the prior single-field {@code setCopyFilter} clobber is gone). A campaign whose vertical
     * matches no registered filter is sent unfiltered (byte-identical to pre-T1). Null is ignored.
     */
    public void registerCopyFilter(@Nullable NurtureCopyFilter copyFilter) {
        if (copyFilter != null) {
            copyFilters.add(copyFilter);
        }
    }

    /**
     * Compose the message for one step + contact, with no vertical (applies no vertical-specific filter).
     * Retained for ergonomics / non-vertical callers; equivalent to {@link #compose(NurtureCadenceStep,
     * Contact, String)} with a {@code null} vertical.
     */
    public Mono<ComposedMessage> compose(NurtureCadenceStep step, Contact contact) {
        return compose(step, contact, null);
    }

    /**
     * Compose the message for one step + contact, then apply the registered {@link NurtureCopyFilter} (if
     * any) whose vertical matches {@code campaignVertical}. When no registered filter matches — none
     * registered (the default) or a null/legacy untagged {@code campaignVertical} — this is byte-identical
     * to the raw composition.
     *
     * @param step             the cadence step (carries channel + templates + the aiPersonalize flag)
     * @param contact          the recipient (for placeholder substitution)
     * @param campaignVertical the composing campaign's vertical tag (selects the matching filter; may be
     *                         null/legacy ⇒ no vertical-specific filter applied)
     * @return the composed message (never errors — AI failure degrades to the template; a filter failure
     *         degrades to the unfiltered body)
     */
    public Mono<ComposedMessage> compose(NurtureCadenceStep step, Contact contact,
                                         @Nullable String campaignVertical) {
        return composeRaw(step, contact).flatMap(msg -> applyFilter(msg, contact, campaignVertical));
    }

    /**
     * Apply the registered {@link NurtureCopyFilter} whose {@link NurtureCopyFilter#appliesTo(String)}
     * matches {@code campaignVertical} (the GATE-2 vertical-scoped dispatch). No matching filter → the
     * message is returned unchanged (byte-identical to pre-T1). The <strong>first</strong> matching filter
     * wins (verticals are mutually exclusive in practice; the first-match rule is deterministic regardless).
     * A filter that replaces the body returns a new {@link ComposedMessage} with the safe body
     * (channel/subject/aiApplied preserved). Best-effort: a filter error degrades to the unfiltered message
     * (logged), never drops the send.
     */
    private Mono<ComposedMessage> applyFilter(ComposedMessage msg, Contact contact,
                                              @Nullable String campaignVertical) {
        NurtureCopyFilter match = filterFor(campaignVertical);
        if (match == null) {
            return Mono.just(msg);
        }
        return match.filter(msg.channel(), msg.body(), contact)
                .map(result -> {
                    if (!result.replaced()) {
                        return msg;
                    }
                    log.info("Nurture copy filter ({}) replaced a {} body (reason: {})",
                            match.vertical(), msg.channel(), result.reason());
                    return new ComposedMessage(msg.channel(), msg.subject(), result.body(), msg.aiApplied());
                })
                .onErrorResume(e -> {
                    log.warn("Nurture copy filter ({}) failed (best-effort, using unfiltered body): {}",
                            match.vertical(), e.toString());
                    return Mono.just(msg);
                });
    }

    /** The first registered filter that applies to {@code campaignVertical}, or null if none matches. */
    @Nullable
    private NurtureCopyFilter filterFor(@Nullable String campaignVertical) {
        for (NurtureCopyFilter f : copyFilters) {
            if (f.appliesTo(campaignVertical)) {
                return f;
            }
        }
        return null;
    }

    /** The raw template-render + best-effort AI-personalize composition (pre-T1 behavior, unchanged). */
    private Mono<ComposedMessage> composeRaw(NurtureCadenceStep step, Contact contact) {
        if (step.channel() == NurtureChannel.EMAIL) {
            String subject = render(step.emailSubject(), contact);
            String body = render(step.emailBody(), contact);
            if (!step.aiPersonalize()) {
                return Mono.just(ComposedMessage.email(subject, body, false));
            }
            return personalize(body)
                    .map(rewritten -> ComposedMessage.email(subject, rewritten, true))
                    .onErrorResume(e -> degrade(e, ComposedMessage.email(subject, body, false)));
        }
        // SMS
        String smsBody = render(step.smsBody(), contact);
        if (!step.aiPersonalize()) {
            return Mono.just(ComposedMessage.sms(smsBody, false));
        }
        return personalize(smsBody)
                .map(rewritten -> ComposedMessage.sms(rewritten, true))
                .onErrorResume(e -> degrade(e, ComposedMessage.sms(smsBody, false)));
    }

    /** Best-effort AI rewrite; a blank model answer is treated as "no rewrite" (keep the template). */
    private Mono<String> personalize(String rendered) {
        return aiAssistService.ask(new AiAssistService.AskRequest(null, AI_SYSTEM_PROMPT + "\n\n"
                        + "Rewrite this message:\n" + rendered))
                .map(AiAssistService.AiAnswer::text)
                .filter(t -> t != null && !t.isBlank())
                .switchIfEmpty(Mono.just(rendered));
    }

    private static Mono<ComposedMessage> degrade(Throwable e, ComposedMessage templated) {
        log.warn("Nurture AI personalize failed (best-effort, degrading to template): {}", e.toString());
        return Mono.just(templated);
    }

    /** Minimal placeholder substitution — {@code {firstName}} / {@code {name}}. Null-safe. */
    private static String render(String template, Contact contact) {
        if (template == null) {
            return "";
        }
        String first = contact.getFirstName() != null ? contact.getFirstName() : "";
        String name = contact.getDisplayName() != null ? contact.getDisplayName()
                : (first.isBlank() ? "" : first);
        return template
                .replace("{firstName}", first)
                .replace("{name}", name);
    }

    /**
     * A composed, ready-to-send message. For SMS only {@link #body} is set; for EMAIL both
     * {@link #subject} and {@link #body} are set.
     *
     * @param channel   the channel
     * @param subject   email subject (null for SMS)
     * @param body      the message body (SMS text / email HTML)
     * @param aiApplied true iff the AI rewrite was applied
     */
    public record ComposedMessage(NurtureChannel channel, String subject, String body, boolean aiApplied) {
        static ComposedMessage sms(String body, boolean aiApplied) {
            return new ComposedMessage(NurtureChannel.SMS, null, body, aiApplied);
        }

        static ComposedMessage email(String subject, String body, boolean aiApplied) {
            return new ComposedMessage(NurtureChannel.EMAIL, subject, body, aiApplied);
        }
    }
}
