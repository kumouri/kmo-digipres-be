package com.kumouri.kmodigipresbe.service.responder;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.responder.ConversationState;
import com.kumouri.kmodigipresbe.model.responder.IntentClassification;
import com.kumouri.kmodigipresbe.model.responder.ReplyLogEntry;
import com.kumouri.kmodigipresbe.model.responder.ResponderConfig;
import com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.responder.ReplyLogRepository;
import com.kumouri.kmodigipresbe.repository.responder.ResponderConfigRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * E2 — the reusable inbound-SMS <strong>intent router</strong>: the orchestrator the shipped
 * {@code InboundSmsService} delegates to from its {@code IGNORED} fallthrough (the one permitted seam).
 * It classifies a free-text inbound message and routes it to a matching {@link IntentHandler} (scoped by
 * the tenant's configured vertical), else a generic {@link DefaultHandoffIntentHandler}.
 *
 * <h2>The keystone no-op invariant (design directive #3)</h2>
 * The router activates ONLY for a tenant that has a usable {@link ResponderConfig} (a row,
 * {@code enabled=true}, ≥1 intent). <strong>Absent / disabled / empty-intents config →
 * {@link Outcome#IGNORED}</strong>, so a default tenant behaves byte-identically to before this engine
 * existed. This is what makes wiring the generic delegation on by default safe.
 *
 * <h2>Consent + safety (design directive #3)</h2>
 * <ol>
 *   <li><strong>Never respond to an opted-out contact</strong> — if any contact matching the sender's
 *       phone carries the CF-2 {@code sms-opt-out} tag, the router returns {@code IGNORED} (no reply, no
 *       handler effect — the strictest reading: a STOPped contact is left entirely alone here; STOP
 *       itself is handled upstream in {@code InboundSmsService}, unchanged).</li>
 *   <li><strong>Per-tenant reply cap</strong> — at/over {@link ResponderConfig#getReplyCapPerContactPerDay()}
 *       replies to this sender in the rolling day, the handler still runs but no reply is sent.</li>
 * </ol>
 *
 * <h2>Reactive / idempotency (design directive #5)</h2>
 * All effects run under a synthetic {@code TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"))}
 * (the {@code route()} entry establishes none — mirrors {@code InboundSmsService.optOut}). The config
 * load + conversation find-or-create are <strong>explicit-boolean</strong>; the only {@code switchIfEmpty}
 * here is none — there is no conditional-create seam in the router (the classifier owns the sole genuine
 * house-key {@code switchIfEmpty}). No {@code .block()}; the classifier + Twilio send are reactive.
 */
@Slf4j
public class InboundIntentRouter {

    /** What the router resolved an inbound message to (mapped onto {@code InboundOutcome} by the seam). */
    public enum Outcome { HANDLED, IGNORED }

    private final ResponderConfigRepository configs;
    private final ContactRepository contacts;
    private final ReplyLogRepository replyLog;
    private final ConversationStateService conversationStateService;
    private final InboundIntentClassifier classifier;
    private final List<IntentHandler> handlers;
    private final TwilioSmsService twilioSmsService;
    private final DomainEventPublisher events;

    public InboundIntentRouter(ResponderConfigRepository configs,
                               ContactRepository contacts,
                               ReplyLogRepository replyLog,
                               ConversationStateService conversationStateService,
                               InboundIntentClassifier classifier,
                               List<IntentHandler> handlers,
                               TwilioSmsService twilioSmsService,
                               DomainEventPublisher events) {
        this.configs = configs;
        this.contacts = contacts;
        this.replyLog = replyLog;
        this.conversationStateService = conversationStateService;
        this.classifier = classifier;
        this.handlers = handlers == null ? List.of() : handlers;
        this.twilioSmsService = twilioSmsService;
        this.events = events;
    }

    /**
     * Handle one inbound message. Returns {@link Outcome#HANDLED} iff the responder took meaningful
     * action (a matched handler ran), else {@link Outcome#IGNORED} (which the seam maps back to the
     * shipped {@code IGNORED} no-op). Runs entirely under a synthetic tenant context.
     */
    public Mono<Outcome> handle(UUID tenantId, String fromPhone, String toPhone, String body) {
        if (fromPhone == null || fromPhone.isBlank()) {
            return Mono.just(Outcome.IGNORED);
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("INTEGRATION_TWILIO"));
        return configs.findByTenantId(tenantId)
                // No config row → the router is a no-op (= byte-identical to today's IGNORED).
                .flatMap(config -> usable(config)
                        ? routeWithConfig(tenantId, fromPhone, toPhone, body, config)
                        : Mono.just(Outcome.IGNORED))
                .defaultIfEmpty(Outcome.IGNORED)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    /** A config is usable iff it exists, is enabled, and has at least one named intent. */
    private static boolean usable(ResponderConfig config) {
        return config != null && config.isEnabled()
                && config.getIntents() != null && !config.getIntents().isEmpty();
    }

    private Mono<Outcome> routeWithConfig(UUID tenantId, String fromPhone, String toPhone, String body,
                                          ResponderConfig config) {
        // Consent gate: an opted-out sender is left entirely alone (no reply, no handler).
        return isOptedOut(tenantId, fromPhone)
                .flatMap(optedOut -> {
                    if (optedOut) {
                        log.debug("Responder: sender {} is opted out for tenant {} — IGNORED", fromPhone, tenantId);
                        return Mono.just(Outcome.IGNORED);
                    }
                    return conversationStateService.findOrCreate(tenantId, fromPhone, config.getVertical())
                            .flatMap(state -> classifyRouteAndReply(
                                    tenantId, fromPhone, toPhone, body, config, state));
                });
    }

    private Mono<Outcome> classifyRouteAndReply(UUID tenantId, String fromPhone, String toPhone, String body,
                                                ResponderConfig config, ConversationState state) {
        return classifier.classify(body, config.getIntents(), config.getModel(),
                        config.getSystemPromptOverride())
                .flatMap(classification -> {
                    emitClassified(tenantId, fromPhone, classification, config.getVertical());
                    IntentHandler handler = selectHandler(config.getVertical(), classification.intent());
                    IntentHandler.HandlerContext hctx = new IntentHandler.HandlerContext(
                            tenantId, fromPhone, toPhone, body, classification, state);
                    return handler.handle(hctx)
                            .defaultIfEmpty(IntentHandler.HandlerResult.ignored())
                            .flatMap(result -> applyResult(
                                    tenantId, fromPhone, config, classification, state, handler, result));
                });
    }

    /**
     * Select the handler: the first registered NON-default handler whose {@code supports(vertical,intent)}
     * is true; else the configured {@code fallbackHandlerKey} handler; else the built-in default-handoff.
     * An {@code UNKNOWN} intent always routes to the fallback/default (no specific handler claims it).
     */
    private IntentHandler selectHandler(String vertical, String intent) {
        if (intent != null && !IntentClassification.UNKNOWN.equals(intent)) {
            for (IntentHandler h : handlers) {
                if (DefaultHandoffIntentHandler.KEY.equals(h.key())) {
                    continue; // the default is the fallback, never a first-pass match
                }
                if (h.supports(vertical, intent)) {
                    return h;
                }
            }
        }
        return defaultHandler();
    }

    private IntentHandler defaultHandler() {
        for (IntentHandler h : handlers) {
            if (DefaultHandoffIntentHandler.KEY.equals(h.key())) {
                return h;
            }
        }
        // Should not happen — ResponderAutoConfiguration always registers the default-handoff bean.
        for (IntentHandler h : handlers) {
            return h;
        }
        throw new IllegalStateException("No IntentHandler registered (default-handoff missing)");
    }

    private Mono<Outcome> applyResult(UUID tenantId, String fromPhone, ResponderConfig config,
                                      IntentClassification classification, ConversationState state,
                                      IntentHandler handler, IntentHandler.HandlerResult result) {
        Mono<Boolean> replyMono = Mono.just(false);
        if (result.replyText() != null && !result.replyText().isBlank()) {
            replyMono = sendReplyWithinCap(tenantId, fromPhone, config, result.replyText());
        }
        return replyMono.flatMap(replied -> {
            emitHandled(tenantId, fromPhone, classification, handler.key(), replied);
            return conversationStateService.recordTurn(state, classification)
                    .thenReturn(result.handled() ? Outcome.HANDLED : Outcome.IGNORED);
        });
    }

    /**
     * Send the reply iff under the per-tenant per-sender rolling-day cap; record a {@link ReplyLogEntry}
     * on success. Returns whether a reply was actually sent. Best-effort send (a Twilio failure is
     * logged + swallowed — the intake already happened).
     */
    private Mono<Boolean> sendReplyWithinCap(UUID tenantId, String fromPhone, ResponderConfig config,
                                             String replyText) {
        int cap = config.getReplyCapPerContactPerDay();
        if (cap <= 0) {
            return Mono.just(false);
        }
        Instant windowStart = Instant.now().minus(Duration.ofDays(1));
        return replyLog.countByTenantIdAndPhoneAndSentAtAfter(tenantId, fromPhone, windowStart)
                .defaultIfEmpty(0L)
                .flatMap(count -> {
                    if (count >= cap) {
                        log.debug("Responder: reply cap {} reached for {} (tenant {}) — not replying",
                                cap, fromPhone, tenantId);
                        return Mono.just(false);
                    }
                    SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                            .to(new PhoneContact(fromPhone))
                            .body(replyText)
                            .build();
                    return twilioSmsService.sendSms(req)
                            .flatMap(ok -> replyLog.save(ReplyLogEntry.builder()
                                            .id(UUID.randomUUID())
                                            .tenantId(tenantId)
                                            .phone(fromPhone)
                                            .sentAt(Instant.now())
                                            .build())
                                    .thenReturn(true))
                            .onErrorResume(e -> {
                                log.warn("Responder reply SMS failed (best-effort, ignored): {}",
                                        e.getMessage());
                                return Mono.just(false);
                            });
                });
    }

    /** True iff any contact matching the sender phone carries the CF-2 {@code sms-opt-out} tag. */
    private Mono<Boolean> isOptedOut(UUID tenantId, String fromPhone) {
        return contacts.findByTenantAndPhoneNumber(tenantId, fromPhone)
                .any(InboundIntentRouter::hasOptOutTag)
                .defaultIfEmpty(false);
    }

    private static boolean hasOptOutTag(Contact contact) {
        Set<String> tags = contact.getTags();
        return tags != null && tags.contains(RiskTieredPreventionService.SMS_OPT_OUT_TAG);
    }

    private void emitClassified(UUID tenantId, String fromPhone, IntentClassification classification,
                                String vertical) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("phone", fromPhone);
        payload.put("intent", classification.intent());
        payload.put("confidence", classification.confidence());
        payload.put("vertical", vertical);
        events.publish(DomainEvent.of(
                DomainEventType.RESPONDER_MESSAGE_CLASSIFIED, tenantId, null, payload));
    }

    private void emitHandled(UUID tenantId, String fromPhone, IntentClassification classification,
                             String handlerKey, boolean replied) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("phone", fromPhone);
        payload.put("intent", classification.intent());
        payload.put("handlerKey", handlerKey);
        payload.put("replied", replied);
        events.publish(DomainEvent.of(
                DomainEventType.RESPONDER_INTENT_HANDLED, tenantId, null, payload));
    }
}
