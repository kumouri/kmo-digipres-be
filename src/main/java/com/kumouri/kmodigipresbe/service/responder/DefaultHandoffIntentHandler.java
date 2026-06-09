package com.kumouri.kmodigipresbe.service.responder;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnection;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.Map;

/**
 * E2 — the built-in generic handoff handler (key {@code "default-handoff"}). The router falls back to
 * this whenever no specific {@link IntentHandler} matched OR the intent is {@code UNKNOWN} (design
 * directive #2). It best-effort notifies staff (email + SMS to the per-tenant Twilio
 * {@code IntegrationConnection.config.notifyEmail}/{@code notifyPhone} — <strong>NOT hardcoded</strong>;
 * the {@code TwilioVoicemailService.notifyRob} precedent) and returns a generic "a team member will
 * follow up" reply for the router to send (subject to the consent gate + reply cap).
 *
 * <p>{@link #supports(String, String)} returns true for every vertical/intent so it is a universal
 * fallback — but the router excludes the default-handoff key from its first (specific-handler) pass and
 * only invokes it as the fallback, so {@code supports} returning true does not steal a message from a
 * specific handler.
 *
 * <p>Best-effort: a missing notify target or a send failure is swallowed ({@code onErrorResume}) — the
 * handoff still returns its reply, and the inbound message is never dropped. Error codes reused, not
 * re-allocated: {@code 2530-2532} (Twilio SMS), the email path through {@link EmailService}.
 */
@Slf4j
public class DefaultHandoffIntentHandler implements IntentHandler {

    public static final String KEY = "default-handoff";

    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSmsService;
    private final EmailService emailService;
    private final DomainEventPublisher events;
    private final String handoffReply;
    private final String notifyFromAddress;

    public DefaultHandoffIntentHandler(IntegrationConnectionRepository connections,
                                       TwilioSmsService twilioSmsService,
                                       EmailService emailService,
                                       DomainEventPublisher events,
                                       String handoffReply,
                                       String notifyFromAddress) {
        this.connections = connections;
        this.twilioSmsService = twilioSmsService;
        this.emailService = emailService;
        this.events = events;
        this.handoffReply = (handoffReply == null || handoffReply.isBlank())
                ? "Thanks for your message! A team member will follow up with you shortly."
                : handoffReply;
        this.notifyFromAddress = notifyFromAddress == null ? "" : notifyFromAddress;
    }

    @Override
    public String key() {
        return KEY;
    }

    /** The universal fallback — the router only invokes this when no specific handler matched. */
    @Override
    public boolean supports(String vertical, String intent) {
        return true;
    }

    @Override
    public Mono<HandlerResult> handle(HandlerContext ctx) {
        String intent = ctx.classification() == null ? null : ctx.classification().intent();
        // Notify staff best-effort, then emit the advisory handoff event, then return the generic reply.
        return connections.findByTenantIdAndProvider(ctx.tenantId(), TwilioSmsService.PROVIDER)
                .flatMap(conn -> notifyStaff(conn, ctx))
                .onErrorResume(e -> {
                    log.warn("Responder handoff notify failed (best-effort, ignored): {}", e.getMessage());
                    return Mono.empty();
                })
                .then(Mono.fromRunnable(() -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("phone", ctx.fromPhone());
                    payload.put("intent", intent);
                    events.publish(DomainEvent.of(
                            DomainEventType.RESPONDER_HANDED_OFF, ctx.tenantId(), null, payload));
                }))
                .thenReturn(HandlerResult.reply(handoffReply));
    }

    /** Best-effort email + SMS to the per-tenant notify targets (NOT hardcoded). */
    private Mono<Void> notifyStaff(IntegrationConnection conn, HandlerContext ctx) {
        Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
        String notifyEmail = config.get("notifyEmail");
        String notifyPhone = config.get("notifyPhone");
        String from = ctx.fromPhone() != null ? ctx.fromPhone() : "(unknown)";
        String summary = "New inbound SMS needs a human: \"" + safe(ctx.body()) + "\" from " + from;

        Mono<Void> emailMono = Mono.empty();
        if (notifyEmail != null && !notifyEmail.isBlank()
                && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
            SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                    .from(new EmailContact(notifyFromAddress))
                    .to(new EmailContact(notifyEmail))
                    .subject("Inbound SMS — follow-up needed (" + from + ")")
                    .body("<p>" + org.springframework.web.util.HtmlUtils.htmlEscape(summary) + "</p>")
                    .build();
            emailMono = emailService.sendSingleEmail(emailReq)
                    .onErrorResume(e -> {
                        log.warn("Responder handoff notify-email failed (ignored): {}", e.getMessage());
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
                        log.warn("Responder handoff notify-SMS failed (ignored): {}", e.getMessage());
                        return Mono.just(false);
                    })
                    .then();
        }
        return emailMono.then(smsMono);
    }

    private static String safe(String body) {
        if (body == null) return "";
        String trimmed = body.strip();
        return trimmed.length() > 160 ? trimmed.substring(0, 157) + "..." : trimmed;
    }
}
