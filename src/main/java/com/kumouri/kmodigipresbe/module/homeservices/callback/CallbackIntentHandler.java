package com.kumouri.kmodigipresbe.module.homeservices.callback;

import com.kumouri.kmodigipresbe.model.activity.Activity;
import com.kumouri.kmodigipresbe.model.activity.ActivityType;
import com.kumouri.kmodigipresbe.model.activity.SubjectType;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.ActivityRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.responder.IntentHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * T5 (Home Services "Instant Callback") — the E2 {@link IntentHandler} that records a caller's callback
 * opt-in reply as a revenue-ranked {@link CallbackRequest}. Auto-discovered by the
 * {@code InboundIntentRouter}'s {@code List<IntentHandler>} inject purely by being a bean (the
 * {@code LogisticsIntentHandler} / {@code DefaultHandoffIntentHandler} precedent) — <strong>no router
 * edit</strong>.
 *
 * <h2>Vertical + intent claim</h2>
 * {@link #supports(String, String)} is true only for {@link CallbackIntents#VERTICAL} (= home) + one of
 * {@link CallbackIntents#CALLBACK_INTENTS}. Any other message → UNKNOWN → the E2 default handoff
 * (unchanged); STOP/opt-out is honored upstream in {@code InboundSmsService.route} (the router also leaves
 * an opted-out sender alone), so the consent gate is never bypassed here.
 *
 * <h2>What it does (the router owns the reply send)</h2>
 * <ol>
 *   <li>Correlate the reply (which carries only the caller phone) back to the originating voicemail: find
 *       the caller {@link Contact} by phone → the most-recent {@link CallbackOfferLog} for that contact →
 *       its {@code callSid} → the DRAFT voicemail {@link WorkOrder} (revenue signal: urgency / jobValueBand
 *       / woId) + the most-recent voicemail {@code Activity} summary (the AI one-liner). All best-effort —
 *       a reply with no prior voicemail still records a card and is never dropped.</li>
 *   <li>Determine {@link CallbackMode} from the intent + parse the requested window (the body + the
 *       classifier's {@code preferredTime} slot) defensively.</li>
 *   <li>Materialize / update a {@link CallbackRequest} — <strong>explicit-boolean</strong> exists-probe
 *       (by callSid, else by the caller's open card) — never {@code switchIfEmpty(create)}. Stamp the
 *       deterministic {@link CallbackRevenueRanker} score.</li>
 *   <li>Record an {@link CallbackFunnelStage#ACCEPTED} funnel row (best-effort).</li>
 *   <li>Return {@link HandlerResult#reply(String)} — the router applies the consent gate + reply cap +
 *       the actual SMS send + the {@code ConversationState} persistence + the advisory events.</li>
 * </ol>
 * The handler does its own domain writes (it runs under the synthetic responder tenant context the router
 * established) but <strong>never sends SMS itself</strong> — the {@code IntentHandler} contract.
 */
@Slf4j
public class CallbackIntentHandler implements IntentHandler {

    public static final String KEY = "home-instant-callback";

    private final CallbackRequestRepository callbackRequests;
    private final CallbackOfferLogRepository offerLogs;
    private final CallbackFunnelLogRepository funnelLogs;
    private final CallbackConfigRepository configs;
    private final ContactRepository contacts;
    private final ActivityRepository activities;
    private final WorkOrderRepository workOrders;

    public CallbackIntentHandler(CallbackRequestRepository callbackRequests,
                                 CallbackOfferLogRepository offerLogs,
                                 CallbackFunnelLogRepository funnelLogs,
                                 CallbackConfigRepository configs,
                                 ContactRepository contacts,
                                 ActivityRepository activities,
                                 WorkOrderRepository workOrders) {
        this.callbackRequests = callbackRequests;
        this.offerLogs = offerLogs;
        this.funnelLogs = funnelLogs;
        this.configs = configs;
        this.contacts = contacts;
        this.activities = activities;
        this.workOrders = workOrders;
    }

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public boolean supports(String vertical, String intent) {
        return CallbackIntents.VERTICAL.equalsIgnoreCase(vertical)
                && intent != null
                && CallbackIntents.CALLBACK_INTENTS.contains(intent.trim().toUpperCase());
    }

    @Override
    public Mono<HandlerResult> handle(HandlerContext ctx) {
        UUID tenantId = ctx.tenantId();
        String fromPhone = ctx.fromPhone();
        String intent = ctx.classification() == null ? null : ctx.classification().intent();
        CallbackMode mode = CallbackIntents.CALLBACK_NOW.equalsIgnoreCase(intent)
                ? CallbackMode.IMMEDIATE : CallbackMode.SCHEDULED;
        String windowText = resolveWindowText(ctx, mode);

        return correlate(tenantId, fromPhone)
                .flatMap(c -> upsertCallback(tenantId, fromPhone, mode, windowText, c)
                        .flatMap(saved -> funnel(tenantId, saved.getCallSid())))
                .then(confirmReply(tenantId, mode))
                .map(HandlerResult::reply)
                .onErrorResume(e -> {
                    // Never drop the opt-in: degrade to a generic confirmation (still HANDLED). The
                    // caller asked for a callback; a correlation/storage hiccup must not lose that.
                    log.warn("Instant-callback handle failed for tenant {} (best-effort, generic "
                            + "confirm): {}", tenantId, e.getMessage());
                    return Mono.just(HandlerResult.reply(defaultConfirm(mode)));
                });
    }

    /** Pick the window text: the classifier {@code preferredTime} slot, else the raw body (SCHEDULED only). */
    private static String resolveWindowText(HandlerContext ctx, CallbackMode mode) {
        if (mode != CallbackMode.SCHEDULED) {
            return null;
        }
        Map<String, String> slots = ctx.classification() == null ? Map.of()
                : ctx.classification().extractedSlots();
        String preferred = slots == null ? null : slots.get(CallbackIntents.SLOT_PREFERRED_TIME);
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return ctx.body() == null || ctx.body().isBlank() ? null : ctx.body().trim();
    }

    /**
     * Best-effort correlation of the reply back to the originating voicemail: contact-by-phone → the
     * most-recent {@link CallbackOfferLog} → its CallSid → the DRAFT voicemail WorkOrder (revenue signal)
     * + the voicemail Activity summary. Every miss degrades to a null field — the card is still recorded.
     */
    private Mono<Correlation> correlate(UUID tenantId, String fromPhone) {
        if (fromPhone == null || fromPhone.isBlank()) {
            return Mono.just(Correlation.empty());
        }
        return contacts.findByTenantAndPhoneNumber(tenantId, fromPhone)
                .next()
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(contactOpt -> {
                    if (contactOpt.isEmpty()) {
                        return Mono.just(Correlation.empty());
                    }
                    Contact contact = contactOpt.get();
                    return offerLogs.findFirstByTenantIdAndContactIdOrderByOfferedAtDesc(
                                    tenantId, contact.getId())
                            .map(Optional::of)
                            .defaultIfEmpty(Optional.empty())
                            .flatMap(offerOpt -> {
                                String callSid = offerOpt.map(CallbackOfferLog::getCallSid).orElse(null);
                                return enrich(tenantId, contact.getId(), callSid);
                            });
                })
                .onErrorResume(e -> {
                    log.warn("Instant-callback correlate failed for tenant {} (best-effort, empty): {}",
                            tenantId, e.getMessage());
                    return Mono.just(Correlation.empty());
                });
    }

    /** Load the DRAFT WorkOrder (by CallSid) + the voicemail Activity summary for the revenue signal. */
    private Mono<Correlation> enrich(UUID tenantId, UUID contactId, String callSid) {
        Mono<Optional<WorkOrder>> woMono = callSid == null
                ? Mono.just(Optional.empty())
                : workOrders.findAllByTenantIdAndStatus(tenantId, WorkOrderStatus.DRAFT)
                        .filter(wo -> callSid.equals(customField(wo, "callSid")))
                        .next()
                        .map(Optional::of)
                        .defaultIfEmpty(Optional.empty());

        Mono<Optional<String>> summaryMono = contactId == null
                ? Mono.just(Optional.empty())
                : activities.findAllByTenantIdAndTypeAndSubjectTypeAndSubjectIdOrderByOccurredAtDesc(
                                tenantId, ActivityType.CALL, SubjectType.CONTACT, contactId)
                        .next()
                        .map(a -> Optional.ofNullable(a.getSummary()))
                        .defaultIfEmpty(Optional.empty());

        return Mono.zip(woMono, summaryMono)
                .map(t -> {
                    WorkOrder wo = t.getT1().orElse(null);
                    String summary = t.getT2().orElse(null);
                    return new Correlation(
                            contactId,
                            callSid,
                            wo == null ? null : wo.getId(),
                            wo == null ? null : customField(wo, "urgency"),
                            wo == null ? null : customField(wo, "jobValueBand"),
                            summary);
                });
    }

    /**
     * Materialize the callback card, idempotent via an explicit-boolean exists-probe (never
     * {@code switchIfEmpty(create)}). Dedupe by CallSid when known, else by the caller's most-recent open
     * REQUESTED card — a re-reply updates the window in place rather than creating a second card.
     */
    private Mono<CallbackRequest> upsertCallback(UUID tenantId, String fromPhone, CallbackMode mode,
                                                 String windowText, Correlation c) {
        Instant requestedAt = RequestedWindowParser.parse(windowText);
        Instant rankRef = requestedAt != null ? requestedAt : Instant.now();
        long score = CallbackRevenueRanker.score(c.jobValueBand(), c.urgency(), rankRef);

        Mono<Optional<CallbackRequest>> existing = c.callSid() != null
                ? callbackRequests.findByTenantIdAndCallSid(tenantId, c.callSid())
                        .map(Optional::of).defaultIfEmpty(Optional.empty())
                : callbackRequests.findFirstByTenantIdAndFromPhoneAndStatusOrderByCreatedAtDesc(
                                tenantId, fromPhone, CallbackStatus.REQUESTED)
                        .map(Optional::of).defaultIfEmpty(Optional.empty());

        return existing.flatMap(opt -> {
            if (opt.isPresent()) {
                CallbackRequest updated = opt.get().toBuilder()
                        .mode(mode)
                        .requestedWindowText(windowText)
                        .requestedAt(requestedAt)
                        .revenueScore(score)
                        .build();
                return callbackRequests.save(updated);
            }
            CallbackRequest fresh = CallbackRequest.builder()
                    .id(UUID.randomUUID())
                    .tenantId(tenantId)
                    .contactId(c.contactId())
                    .fromPhone(fromPhone)
                    .callSid(c.callSid())
                    .workOrderId(c.workOrderId())
                    .mode(mode)
                    .requestedWindowText(windowText)
                    .requestedAt(requestedAt)
                    .status(CallbackStatus.REQUESTED)
                    .summaryLine(c.summaryLine())
                    .urgency(c.urgency())
                    .jobValueBand(c.jobValueBand())
                    .revenueScore(score)
                    .build();
            return callbackRequests.save(fresh)
                    // A concurrent second-reply that lost the create race on the (tenant, callSid) unique
                    // index → reload the winner (never a duplicate card).
                    .onErrorResume(DuplicateKeyException.class, dk ->
                            c.callSid() == null
                                    ? Mono.error(dk)
                                    : callbackRequests.findByTenantIdAndCallSid(tenantId, c.callSid()));
        });
    }

    /** Record the ACCEPTED funnel row (best-effort — analytics never break handling). */
    private Mono<Void> funnel(UUID tenantId, String callSid) {
        return funnelLogs.save(CallbackFunnelLog.builder()
                        .id(UUID.randomUUID())
                        .tenantId(tenantId)
                        .stage(CallbackFunnelStage.ACCEPTED)
                        .callSid(callSid)
                        .occurredAt(Instant.now())
                        .build())
                .then()
                .onErrorResume(e -> {
                    log.warn("Instant-callback ACCEPTED funnel write failed (best-effort, ignored): {}",
                            e.getMessage());
                    return Mono.empty();
                });
    }

    /**
     * The confirmation reply — the per-tenant {@link CallbackConfig} copy for this mode, falling back to
     * the generic {@link CallbackCopy} default when the tenant has no config / a blank field. Resolved
     * inside the reactive chain so the router sends the right text.
     */
    private Mono<String> confirmReply(UUID tenantId, CallbackMode mode) {
        return configs.findByTenantId(tenantId)
                .map(cfg -> {
                    String copy = mode == CallbackMode.IMMEDIATE
                            ? cfg.getImmediateConfirmMessage() : cfg.getScheduledConfirmMessage();
                    return (copy == null || copy.isBlank()) ? defaultConfirm(mode) : copy.trim();
                })
                .defaultIfEmpty(defaultConfirm(mode));
    }

    /** The generic, config-free confirmation copy for this mode. */
    private static String defaultConfirm(CallbackMode mode) {
        return mode == CallbackMode.IMMEDIATE
                ? CallbackCopy.DEFAULT_IMMEDIATE_CONFIRM
                : CallbackCopy.DEFAULT_SCHEDULED_CONFIRM;
    }

    private static String customField(WorkOrder wo, String key) {
        Map<String, Object> cf = wo.getCustomFields();
        if (cf == null) {
            return null;
        }
        Object v = cf.get(key);
        return v == null ? null : v.toString();
    }

    /**
     * The best-effort fields recovered from the originating voicemail. Any field may be null (a reply with
     * no prior voicemail, or a correlation miss) — the card is recorded regardless.
     */
    private record Correlation(UUID contactId, String callSid, UUID workOrderId, String urgency,
                               String jobValueBand, String summaryLine) {
        static Correlation empty() {
            return new Correlation(null, null, null, null, null, null);
        }
    }
}
