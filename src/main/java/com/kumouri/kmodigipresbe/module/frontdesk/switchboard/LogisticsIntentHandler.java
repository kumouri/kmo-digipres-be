package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * T4 (Health "Switchboard AI") — the <strong>logistics-only</strong> intent handler: the front-desk
 * overflow that answers a patient's logistics question (hours, location, accepting-new-patients, booking,
 * reschedule, intake-form, review-request) <strong>from per-tenant {@link SwitchboardConfig}</strong>, in
 * generic PHI-free copy. An E2 {@link IntentHandler} the {@code InboundIntentRouter} auto-discovers via its
 * {@code List<IntentHandler>} inject (the {@code RealEstateNurtureReplyHandler} / {@code
 * DefaultHandoffIntentHandler} precedent) — <strong>no router edit</strong>.
 *
 * <h2>Vertical + intent claim</h2>
 * {@link #supports(String, String)} returns true only for {@link SwitchboardIntents#VERTICAL} (= health) +
 * one of the seven {@link SwitchboardIntents#LOGISTICS_INTENTS}. It deliberately does NOT claim
 * {@link SwitchboardIntents#CLINICAL_SYMPTOM} — that intent is claimed only by
 * {@link ClinicalTripwireHandler}, so the router routes a clinical message to the tripwire, never here.
 *
 * <h2>Answers from config, never hardcoded</h2>
 * Each answer is rendered from the tenant's {@link SwitchboardConfig} (the {@code ResponderConfig} holds
 * only the intent vocabulary). A per-intent {@code answerOverrides} entry wins; else the typed config
 * fields are rendered; a tenant with no config (or a blank field) degrades to a safe generic
 * acknowledgement — still {@code HANDLED} (the front desk overflow caught it), never a clinical answer.
 *
 * <h2>PHI-free + best-effort</h2>
 * Only logistics copy is ever produced — no procedure / provider / diagnosis. A deflection row
 * ({@link SwitchboardDeflectionCategory#LOGISTICS}) is recorded best-effort (analytics never break
 * handling). The router (not this handler) owns the consent gate, the reply cap, the actual SMS send, and
 * the conversation-state persistence — so this handler only computes the reply text + records the
 * deflection.
 */
@Slf4j
public class LogisticsIntentHandler implements IntentHandler {

    public static final String KEY = "health-switchboard-logistics";

    private static final String FALLBACK_ACK =
            "Thanks for reaching out! We'll get right back to you. For anything urgent, please call our "
            + "office.";

    private final SwitchboardConfigRepository configs;
    private final SwitchboardDeflectionService deflection;

    public LogisticsIntentHandler(SwitchboardConfigRepository configs,
                                  SwitchboardDeflectionService deflection) {
        this.configs = configs;
        this.deflection = deflection;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean supports(String vertical, String intent) {
        return SwitchboardIntents.VERTICAL.equalsIgnoreCase(vertical)
                && intent != null
                && SwitchboardIntents.LOGISTICS_INTENTS.contains(intent.trim().toUpperCase());
    }

    @Override
    public Mono<HandlerResult> handle(HandlerContext ctx) {
        String intent = ctx.classification() == null ? null : ctx.classification().intent();
        UUID tenantId = ctx.tenantId();
        return configs.findByTenantId(tenantId)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(cfg -> {
                    String answer = renderAnswer(cfg.orElse(null), intent);
                    return deflection.record(tenantId, SwitchboardDeflectionCategory.LOGISTICS)
                            .thenReturn(HandlerResult.reply(answer));
                })
                .onErrorResume(e -> {
                    // Never drop the message: degrade to a safe generic ack (still HANDLED).
                    log.warn("Switchboard logistics handle failed (best-effort, generic ack): {}",
                            e.getMessage());
                    return deflection.record(tenantId, SwitchboardDeflectionCategory.LOGISTICS)
                            .thenReturn(HandlerResult.reply(FALLBACK_ACK));
                });
    }

    /**
     * Render the PHI-free logistics answer for {@code intent} from the tenant config. A per-intent
     * {@code answerOverrides} entry wins; else the typed field is rendered; a missing field falls back to
     * a safe generic acknowledgement (never a clinical answer).
     */
    private static String renderAnswer(SwitchboardConfig cfg, String intent) {
        if (cfg == null || intent == null) {
            return FALLBACK_ACK;
        }
        String canonical = intent.trim().toUpperCase();
        if (cfg.getAnswerOverrides() != null) {
            String override = cfg.getAnswerOverrides().get(canonical);
            if (override != null && !override.isBlank()) {
                return override;
            }
        }
        return switch (canonical) {
            case SwitchboardIntents.HOURS -> nonBlank(cfg.getHoursText())
                    ? "Our hours are: " + cfg.getHoursText().trim()
                    : FALLBACK_ACK;
            case SwitchboardIntents.LOCATION -> nonBlank(cfg.getLocationText())
                    ? "You can find us at: " + cfg.getLocationText().trim()
                    : FALLBACK_ACK;
            case SwitchboardIntents.ACCEPTING_NEW_PATIENTS -> acceptingAnswer(cfg);
            case SwitchboardIntents.BOOK_APPOINTMENT -> nonBlank(cfg.getBookingInstructions())
                    ? "To book an appointment: " + cfg.getBookingInstructions().trim()
                    : "We'd be glad to get you scheduled — please call our office and we'll find a time.";
            case SwitchboardIntents.RESCHEDULE -> nonBlank(cfg.getRescheduleInstructions())
                    ? "To reschedule or cancel: " + cfg.getRescheduleInstructions().trim()
                    : "No problem — please call our office and we'll update your appointment.";
            case SwitchboardIntents.INTAKE_FORM -> nonBlank(cfg.getIntakeFormUrl())
                    ? "You can complete our new-patient forms here: " + cfg.getIntakeFormUrl().trim()
                    : "We'll get your new-patient paperwork over to you — please call our office.";
            case SwitchboardIntents.REVIEW_REQUEST -> nonBlank(cfg.getReviewLinkUrl())
                    ? "We'd love your feedback! You can leave a review here: " + cfg.getReviewLinkUrl().trim()
                    : "Thank you! We'd love your feedback — please call our office and we'll share where to "
                            + "leave a review.";
            default -> FALLBACK_ACK;
        };
    }

    private static String acceptingAnswer(SwitchboardConfig cfg) {
        if (nonBlank(cfg.getAcceptingNewPatientsText())) {
            return cfg.getAcceptingNewPatientsText().trim();
        }
        return cfg.isAcceptingNewPatients()
                ? "Yes — we're happy to welcome new patients! Please call our office to get started."
                : "We're not taking new patients right now, but please call our office and we'll point you "
                        + "in the right direction.";
    }

    private static boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }
}
