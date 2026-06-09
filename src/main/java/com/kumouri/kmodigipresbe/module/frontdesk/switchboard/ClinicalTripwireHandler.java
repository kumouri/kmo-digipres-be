package com.kumouri.kmodigipresbe.module.frontdesk.switchboard;

import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityDirection;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.module.frontdesk.reviews.HipaaReplyLint;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.ActivityCrudService;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T4 (Health "Switchboard AI") — the <strong>clinical-message tripwire</strong> (the headline,
 * release-blocking PHI fence). An E2 {@link IntentHandler} that claims the
 * {@link SwitchboardIntents#CLINICAL_SYMPTOM} intent (vertical = health) and, the instant a patient's
 * inbound SMS is classified as clinical/symptom, hands it off to a human <strong>with NO transcript or
 * clinical content retained anywhere</strong>.
 *
 * <h2>Tripwire BEFORE logistics (by construction)</h2>
 * Only this handler claims {@code CLINICAL_SYMPTOM} (the {@link LogisticsIntentHandler} claims only the
 * seven logistics intents), so the E2 router routes a clinical message here — never to a logistics handler.
 *
 * <h2>The PHI fences (FD-2 fence F2 reused as a pattern — schema + data-layer marker + prompt)</h2>
 * <ol>
 *   <li><strong>The raw body is never persisted.</strong> The E2 {@code ConversationState} has no body
 *       field, so the router never stores the patient's words; and this handler writes only a fixed
 *       redaction marker ({@link SwitchboardRedaction#CLINICAL_MESSAGE_REDACTED_MARKER}) as the
 *       {@code Activity.body}, with a generic PHI-free summary + a payload that carries only
 *       {@code {redacted:true, category:"CLINICAL_TRIPWIRE"}}. A patient saying "I have chest pain" never
 *       lands in a stored, queryable record.</li>
 *   <li><strong>No clinical slots persist.</strong> The one model-controlled surface the router persists
 *       ({@code recordTurn} merges {@code classification.extractedSlots()}) is kept empty by the per-tenant
 *       PHI-forbidding classifier prompt ({@link SwitchboardIntents#HEALTH_CLASSIFIER_PROMPT}) — the last
 *       fence, never the only one.</li>
 *   <li><strong>Defensive structural scan.</strong> This handler additionally runs the inbound through the
 *       shipped {@link HipaaReplyLint} and logs (never persists) any flags — a belt-and-braces audit
 *       signal; it never changes behavior and never writes the inbound text.</li>
 * </ol>
 *
 * <h2>What it does</h2>
 * find-or-create the patient Contact by phone (explicit-boolean — never {@code switchIfEmpty(create)}) →
 * write the redaction-only callback {@link Activity} via the UNCHANGED {@link ActivityCrudService#create} →
 * best-effort notify staff (email + SMS to the per-tenant {@code IntegrationConnection(twilio).config
 * .notifyEmail/notifyPhone} — NOT hardcoded; the {@code DefaultHandoffIntentHandler} precedent) → record a
 * {@link SwitchboardDeflectionCategory#TRIPWIRE} deflection row → return the safe handoff reply (the router
 * sends it, subject to the consent gate + cap). All effects run under the synthetic tenant context the
 * router established. <strong>Best-effort throughout</strong>: a notify / activity / analytics failure
 * never drops the handoff reply (code {@code 4390} is the advisory marker, logged, never thrown).
 */
@Slf4j
public class ClinicalTripwireHandler implements IntentHandler {

    public static final String KEY = "health-switchboard-tripwire";

    private final ContactRepository contacts;
    private final ActivityCrudService activityCrudService;
    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSmsService;
    private final EmailService emailService;
    private final SwitchboardConfigRepository configs;
    private final SwitchboardDeflectionService deflection;
    private final String notifyFromAddress;

    public ClinicalTripwireHandler(ContactRepository contacts,
                                   ActivityCrudService activityCrudService,
                                   IntegrationConnectionRepository connections,
                                   TwilioSmsService twilioSmsService,
                                   EmailService emailService,
                                   SwitchboardConfigRepository configs,
                                   SwitchboardDeflectionService deflection,
                                   String notifyFromAddress) {
        this.contacts = contacts;
        this.activityCrudService = activityCrudService;
        this.connections = connections;
        this.twilioSmsService = twilioSmsService;
        this.emailService = emailService;
        this.configs = configs;
        this.deflection = deflection;
        this.notifyFromAddress = notifyFromAddress == null ? "" : notifyFromAddress;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean supports(String vertical, String intent) {
        return SwitchboardIntents.VERTICAL.equalsIgnoreCase(vertical)
                && SwitchboardIntents.CLINICAL_SYMPTOM.equalsIgnoreCase(intent);
    }

    @Override
    public Mono<HandlerResult> handle(HandlerContext ctx) {
        UUID tenantId = ctx.tenantId();

        // Defensive structural scan (belt-and-braces) — log flags ONLY; never persist the inbound text.
        int hipaaFlags = HipaaReplyLint.lint(ctx.body()).size();
        if (hipaaFlags > 0) {
            log.debug("Switchboard tripwire: inbound matched {} HIPAA lint term(s) — handing off "
                    + "(content NOT persisted)", hipaaFlags);
        }

        return logRedactedCallback(tenantId, ctx.fromPhone())
                .then(notifyStaff(tenantId).onErrorResume(e -> {
                    log.warn("Switchboard tripwire notify failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                }))
                .then(deflection.record(tenantId, SwitchboardDeflectionCategory.TRIPWIRE))
                .then(resolveSafeReply(tenantId))
                .map(HandlerResult::reply)
                // The handoff reply must never be dropped (4390 advisory) — return a safe default.
                .onErrorResume(e -> {
                    log.warn("Switchboard tripwire handle degraded (4390, best-effort): {}", e.getMessage());
                    return Mono.just(HandlerResult.reply(
                            SwitchboardRedaction.DEFAULT_SAFE_TRIPWIRE_REPLY));
                });
    }

    /**
     * Write the PHI-free redaction-only callback Activity (find-or-create the Contact by phone first).
     * The body is the fixed redaction marker — NEVER the patient's message; the payload carries only the
     * redaction flag + category. Best-effort: a write failure (4390 advisory) is swallowed so the handoff
     * reply still goes out.
     */
    private Mono<Void> logRedactedCallback(UUID tenantId, String fromPhone) {
        return findOrCreateContact(tenantId, fromPhone)
                .flatMap(contact -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("redacted", true);
                    payload.put("category", "CLINICAL_TRIPWIRE");
                    Activity activity = Activity.builder()
                            .tenantId(tenantId)
                            .type(ActivityType.NOTE)
                            .direction(ActivityDirection.INBOUND)
                            .subjectType(SubjectType.CONTACT)
                            .subjectId(contact.getId())
                            .summary(SwitchboardRedaction.TRIPWIRE_SUMMARY)
                            .body(SwitchboardRedaction.CLINICAL_MESSAGE_REDACTED_MARKER)
                            .payload(payload)
                            .build();
                    return activityCrudService.create(activity).then();
                })
                .onErrorResume(e -> {
                    log.warn("Switchboard tripwire callback-activity write failed (4390, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                });
    }

    /** Find-or-create the patient Contact by phone — explicit-boolean (never {@code switchIfEmpty(create)}). */
    private Mono<Contact> findOrCreateContact(UUID tenantId, String fromPhone) {
        if (fromPhone == null || fromPhone.isBlank()) {
            return contacts.save(buildContact(null));
        }
        return contacts.findByTenantAndPhoneNumber(tenantId, fromPhone)
                .next()
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(existing -> existing.isPresent()
                        ? Mono.just(existing.get())
                        : contacts.save(buildContact(fromPhone)));
    }

    private Contact buildContact(String fromPhone) {
        String displayName = fromPhone != null ? "SMS contact " + fromPhone : "SMS contact";
        List<PhoneNumber> phones = new ArrayList<>();
        if (fromPhone != null && !fromPhone.isBlank()) {
            phones.add(PhoneNumber.builder().number(fromPhone).label("sms").build());
        }
        return Contact.builder()
                .type(ContactType.PERSON)
                .displayName(displayName)
                .phones(phones)
                .tags(Set.of("switchboard-lead"))
                .build();
    }

    /**
     * Best-effort email + SMS to the per-tenant notify targets (NOT hardcoded) — a generic, PHI-free
     * "a clinical message came in, please call the patient back" alert. The body NEVER includes the
     * patient's message (only the generic summary). Mirrors {@code DefaultHandoffIntentHandler.notifyStaff}.
     */
    private Mono<Void> notifyStaff(UUID tenantId) {
        return connections.findByTenantIdAndProvider(tenantId, TwilioSmsService.PROVIDER)
                .flatMap(this::notifyVia)
                .then();
    }

    private Mono<Void> notifyVia(IntegrationConnection conn) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");
        String summary = SwitchboardRedaction.TRIPWIRE_SUMMARY;

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject("Patient SMS — care-team callback needed")
                    .body("<p>" + org.springframework.web.util.HtmlUtils.htmlEscape(summary) + "</p>")
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("Switchboard tripwire notify-email failed (ignored): {}", e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }

        Mono<Void> smsMono = Mono.empty();
        if (notifyPhone != null && !notifyPhone.isBlank()) {
            SmsCommunicationRequest smsReq = SmsCommunicationRequest.builder()
                    .to(new PhoneContact(notifyPhone))
                    .body(summary)
                    .build();
            smsMono = twilioSmsService.sendSms(smsReq)
                    .onErrorResume(e -> {
                        log.warn("Switchboard tripwire notify-SMS failed (ignored): {}", e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    /** The per-tenant safe reply (config {@code safeTripwireReply}) else the generic default. */
    private Mono<String> resolveSafeReply(UUID tenantId) {
        return configs.findByTenantId(tenantId)
                .map(cfg -> {
                    String reply = cfg.getSafeTripwireReply();
                    return (reply != null && !reply.isBlank())
                            ? reply
                            : SwitchboardRedaction.DEFAULT_SAFE_TRIPWIRE_REPLY;
                })
                .defaultIfEmpty(SwitchboardRedaction.DEFAULT_SAFE_TRIPWIRE_REPLY)
                .onErrorReturn(SwitchboardRedaction.DEFAULT_SAFE_TRIPWIRE_REPLY);
    }
}
