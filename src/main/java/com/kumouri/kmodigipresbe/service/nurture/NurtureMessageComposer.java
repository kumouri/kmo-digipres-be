package com.kumouri.kmodigipresbe.service.nurture;

import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.nurture.NurtureCadenceStep;
import com.kumouri.kmodigipresbe.model.nurture.NurtureChannel;
import com.kumouri.kmodigipresbe.service.ai.AiAssistService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

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
     * Compose the message for one step + contact.
     *
     * @param step    the cadence step (carries channel + templates + the aiPersonalize flag)
     * @param contact the recipient (for placeholder substitution)
     * @return the composed message (never errors — AI failure degrades to the template)
     */
    public Mono<ComposedMessage> compose(NurtureCadenceStep step, Contact contact) {
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
