package com.kumouri.kmodigipresbe.module.realestate.concierge;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.integration.IntegrationConnectionRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.deal.Deal;
import com.kumouri.kmodigipresbe.model.request.SingleEmailCommunicationRequest;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.model.scoring.LeadScore;
import com.kumouri.kmodigipresbe.module.realestate.RealEstateAutoConfiguration;
import com.kumouri.kmodigipresbe.module.realestate.model.HotHandoffLog;
import com.kumouri.kmodigipresbe.module.realestate.model.HotHandoffLogRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.DealRepository;
import com.kumouri.kmodigipresbe.repository.TenantRepository;
import com.kumouri.kmodigipresbe.service.EmailService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Real Estate Concierge (RE-2) — the hot-handoff. A dedicated {@code @PostConstruct} subscriber on
 * {@link DomainEventType#LEAD_SCORE_UPDATED} (the UNCHANGED nightly {@code LeadScoringV2Service} stamp),
 * mirroring {@code RiskTieredPreventionService} / {@code RebookingNudgeService}'s
 * {@code events.stream().filter(type).flatMap(handle)} pattern + synthetic {@link TenantContext}
 * (RE-2 §5 / decision 3).
 *
 * <p><strong>The scoring-reuse contract (HARD GATE 1).</strong> RE-2 does NOT modify the scorer and adds no
 * ML. The buyer's concierge-sourced {@code Deal} (materialized by {@link QualificationService}) flows
 * through the shipped nightly scorer, which tiers the Contact HOT/WARM/COLD and emits the existing
 * {@code LEAD_SCORE_UPDATED}. This subscriber listens to THAT event and does the agent alert.
 *
 * <h2>When it fires (and when it no-ops)</h2>
 * <ul>
 *   <li>only for a <strong>HOT</strong> tier ({@link LeadScore#TIER_HOT});</li>
 *   <li>only when the scored Contact has at least one <strong>concierge-sourced realestate Deal</strong>
 *       ({@code customFields.source == "concierge"} — {@link QualificationService#isConciergeSourced});</li>
 *   <li>module-gated: the bean exists only when the realestate module is on, and {@link #process}
 *       re-checks {@code Tenant.enabledModules} membership (defense-in-depth) — a non-realestate or
 *       non-HOT or non-concierge {@code LEAD_SCORE_UPDATED} is a hard no-op (zero blast radius).</li>
 * </ul>
 *
 * <h2>Idempotent + best-effort (HARD GATE 3)</h2>
 * A {@link HotHandoffLog} row is inserted <strong>FIRST</strong>, unique on {@code (tenant, deal)} — a
 * re-fired {@code LEAD_SCORE_UPDATED} (nightly re-score, restart, concurrent emit) loses on a
 * {@code DuplicateKeyException} and does ZERO duplicate notify. Only the winner notifies. Every external
 * call (Twilio SMS, notify email) is wrapped {@code onErrorResume}: a send failure logs + degrades — a
 * Deal/conversation is <strong>never</strong> corrupted, and a Claude/SMS outage never drops the handoff
 * signal (the ledger row + the {@code CONCIERGE_HOT_HANDOFF} event are the durable record). The notify
 * targets are the per-tenant {@code IntegrationConnection(twilio).config.notifyPhone}/{@code notifyEmail}
 * (the {@code TwilioVoicemailService.notifyRob} precedent), NOT hardcoded.
 *
 * <p>Reused error codes only: Twilio {@code 2530-2532} (the reused {@code TwilioSmsService}); RE-2 mints
 * none of its own (the {@code 4260-4262} band is qualification-side). Hand-constructed as a {@code @Bean}
 * by {@code RealEstateAutoConfiguration}; the {@code @PostConstruct} fires the bus subscription at init.
 */
@Slf4j
public class LeadHandoffService {

    private final DomainEventPublisher events;
    private final TenantRepository tenants;
    private final ContactRepository contacts;
    private final DealRepository deals;
    private final HotHandoffLogRepository handoffLog;
    private final IntegrationConnectionRepository connections;
    private final TwilioSmsService twilioSms;
    private final EmailService emailService;
    private final boolean handoffNotify;
    private final String notifyFromAddress;

    public LeadHandoffService(DomainEventPublisher events,
                              TenantRepository tenants,
                              ContactRepository contacts,
                              DealRepository deals,
                              HotHandoffLogRepository handoffLog,
                              IntegrationConnectionRepository connections,
                              TwilioSmsService twilioSms,
                              EmailService emailService,
                              boolean handoffNotify,
                              String notifyFromAddress) {
        this.events = events;
        this.tenants = tenants;
        this.contacts = contacts;
        this.deals = deals;
        this.handoffLog = handoffLog;
        this.connections = connections;
        this.twilioSms = twilioSms;
        this.emailService = emailService;
        this.handoffNotify = handoffNotify;
        this.notifyFromAddress = notifyFromAddress == null ? "" : notifyFromAddress;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> DomainEventType.LEAD_SCORE_UPDATED.equals(e.type()))
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("RE-2 LeadHandoffService: error processing LEAD_SCORE_UPDATED "
                                    + "for tenant {}", e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code LEAD_SCORE_UPDATED} event end-to-end and return
     * when done (so an IT can drive it deterministically without the live event bus + a sleep). Resolves
     * the contact id from the event subject, re-reads the tier off the persisted contact (deterministic),
     * and runs the hot-handoff under a synthetic context.
     */
    public Mono<Void> handle(DomainEvent event) {
        UUID tenantId = event.tenantId();
        UUID contactId = resolveContactId(event);
        if (tenantId == null || contactId == null) {
            return Mono.empty();
        }
        TenantContext ctx = new TenantContext(tenantId, null, Set.of("SYSTEM"));
        return process(tenantId, contactId)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private static UUID resolveContactId(DomainEvent event) {
        if (event.subjectId() != null) {
            return event.subjectId();
        }
        Object raw = event.payload() == null ? null : event.payload().get("contactId");
        if (raw instanceof UUID u) {
            return u;
        }
        if (raw != null) {
            try {
                return UUID.fromString(raw.toString());
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * Defense-in-depth (HARD GATE — zero blast radius): even though only realestate tenants would carry a
     * concierge Deal, re-check the module membership, then re-read the contact's HOT tier off the persisted
     * record (the scorer just saved it) and find its concierge-sourced realestate Deal. A non-realestate /
     * non-HOT / non-concierge update is a clean no-op here.
     */
    private Mono<Void> process(UUID tenantId, UUID contactId) {
        return tenants.findById(tenantId)
                .filter(t -> t.getEnabledModules() != null
                        && t.getEnabledModules().contains(RealEstateAutoConfiguration.MODULE_KEY))
                .flatMap(t -> contacts.findByTenantIdAndId(tenantId, contactId))
                .filter(c -> c.getLeadScore() != null
                        && LeadScore.TIER_HOT.equals(c.getLeadScore().tier()))
                .flatMap(contact -> findConciergeDeal(tenantId, contactId)
                        .flatMap(deal -> claimThenNotify(tenantId, contact.getId(), deal,
                                contact.getLeadScore().score())))
                .then();
    }

    /** The contact's first concierge-sourced realestate Deal (the hot-handoff target), or empty. */
    private Mono<Deal> findConciergeDeal(UUID tenantId, UUID contactId) {
        return deals.findAllByTenantId(tenantId)
                .filter(d -> contactId.equals(d.getPrimaryContactId()))
                .filter(QualificationService::isConciergeSourced)
                .next();
    }

    /**
     * Insert the {@link HotHandoffLog} FIRST (unique (tenant, deal)); a concurrent / re-fired event loses
     * on {@code DuplicateKeyException} = zero duplicate notify. Only the winner proceeds to alert the agent.
     */
    private Mono<Void> claimThenNotify(UUID tenantId, UUID contactId, Deal deal, double score) {
        UUID listingId = QualificationService.conciergeListingId(deal);
        HotHandoffLog ledger = HotHandoffLog.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .dealId(deal.getId())
                .contactId(contactId)
                .listingId(listingId)
                .score(score)
                .handedOffAt(Instant.now())
                .build();
        return handoffLog.save(ledger)
                .onErrorResume(DuplicateKeyException.class, e -> {
                    log.debug("RE-2 hot-handoff: Deal {} already handed off (ledger present) — zero "
                            + "duplicate", deal.getId());
                    return Mono.empty();
                })
                .flatMap(saved -> notifyAgent(tenantId, deal, score)
                        .then(Mono.fromRunnable(() -> events.publish(DomainEvent.of(
                                DomainEventType.CONCIERGE_HOT_HANDOFF, tenantId, deal.getId(),
                                handoffPayload(contactId, deal, listingId, score))))))
                .then();
    }

    /**
     * Best-effort agent alert — SMS + email — to the per-tenant targets on
     * {@code IntegrationConnection(twilio).config} ({@code notifyPhone} / {@code notifyEmail}); NOT
     * hardcoded (the {@code TwilioVoicemailService.notifyRob} precedent). A missing target or send failure
     * is swallowed ({@code onErrorResume}) so the already-durable handoff (ledger + event) is never lost.
     * Gated by {@code kmosf.realestate.handoff-notify} (default off — the demo flips it on).
     */
    private Mono<Void> notifyAgent(UUID tenantId, Deal deal, double score) {
        if (!handoffNotify) {
            log.info("RE-2 hot-handoff: HOT concierge lead (deal {}, score {}) — notify disabled "
                    + "(kmosf.realestate.handoff-notify=false); ledger + event are the signal",
                    deal.getId(), score);
            return Mono.empty();
        }
        return connections.findByTenantIdAndProvider(tenantId, TwilioSmsService.PROVIDER)
                .flatMap(conn -> {
                    Map<String, String> config = conn.getConfig() == null ? Map.of() : conn.getConfig();
                    String notifyPhone = config.get("notifyPhone");
                    String notifyEmail = config.get("notifyEmail");
                    String summary = "Hot buyer lead: " + deal.getTitle()
                            + (deal.getValue() != null
                                    ? " (~$" + deal.getValue().toPlainString() + ")" : "")
                            + " just scored HOT.";

                    Mono<Void> smsMono = Mono.empty();
                    if (notifyPhone != null && !notifyPhone.isBlank()) {
                        smsMono = twilioSms.sendSms(SmsCommunicationRequest.builder()
                                        .to(PhoneContact.builder().e164(notifyPhone).build())
                                        .body(summary + " Follow up now.")
                                        .build())
                                .onErrorResume(e -> {
                                    log.warn("RE-2 hot-handoff notify-SMS failed (best-effort, ignored): "
                                            + "{}", e.toString());
                                    return Mono.just(false);
                                })
                                .then();
                    }

                    Mono<Void> emailMono = Mono.empty();
                    if (notifyEmail != null && !notifyEmail.isBlank()
                            && notifyFromAddress != null && !notifyFromAddress.isBlank()) {
                        SingleEmailCommunicationRequest emailReq = SingleEmailCommunicationRequest.builder()
                                .from(new EmailContact(notifyFromAddress))
                                .to(new EmailContact(notifyEmail))
                                .subject("Hot buyer lead — " + deal.getTitle())
                                .body("<p>" + summary + "</p><p>The concierge has qualified this buyer; "
                                        + "they're ready for a personal follow-up.</p>")
                                .build();
                        emailMono = emailService.sendSingleEmail(emailReq)
                                .onErrorResume(e -> {
                                    log.warn("RE-2 hot-handoff notify-email failed (best-effort, ignored): "
                                            + "{}", e.toString());
                                    return Mono.just(false);
                                })
                                .then();
                    }
                    return smsMono.then(emailMono);
                })
                .switchIfEmpty(Mono.fromRunnable(() -> log.debug(
                        "RE-2 hot-handoff: no Twilio connection / notify targets for tenant {} — handoff "
                                + "recorded, no send", tenantId)));
    }

    private static Map<String, Object> handoffPayload(UUID contactId, Deal deal, UUID listingId,
                                                      double score) {
        Map<String, Object> p = new HashMap<>();
        p.put("contactId", contactId == null ? null : contactId.toString());
        p.put("dealId", deal.getId() == null ? null : deal.getId().toString());
        if (listingId != null) {
            p.put("listingId", listingId.toString());
        }
        p.put("score", score);
        p.put("tier", LeadScore.TIER_HOT);
        if (deal.getValue() != null) {
            p.put("value", deal.getValue());
        }
        return p;
    }
}
