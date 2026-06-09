package com.kumouri.kmodigipresbe.module.ar;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.model.billing.Invoice;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.PhoneContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.model.request.SmsCommunicationRequest;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.repository.InvoiceRepository;
import com.kumouri.kmodigipresbe.integration.twilio.TwilioSmsService;
import com.kumouri.kmodigipresbe.service.billing.StripeCheckoutService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * "Get Paid" AR-3 — the tiered <strong>dunning dispatch</strong> leg (band 4600-4619). A dedicated
 * {@code @PostConstruct} subscriber on the AR-2 {@code INVOICE_OVERDUE_{D3,D7,D14}} domain events that,
 * per crossed tier, drafts a Claude-personalized on-brand reminder ({@link DunningCopyComposer}), mints
 * a one-touch Stripe pay link ({@link StripeCheckoutService}), and sends a single SMS
 * ({@link TwilioSmsService}). It mirrors ChairFill CF-2's
 * {@link com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService} exactly in
 * shape — the {@code events.stream().filter(type).flatMap(handle)} {@code @PostConstruct} subscription
 * on {@code Schedulers.boundedElastic()}, the synthetic {@link TenantContext} {@code contextWrite} for
 * the async handler, the budget-gated AI composer with a defensive literal-template fallback, and the
 * {@code @MockitoBean}-able Twilio send.
 *
 * <p><strong>Why a dedicated subscriber and not a seeded {@code SEND_SMS} WorkflowRule (AR-3 design):</strong>
 * the generic {@code RuleActionDispatcher.sendSms} body comes from {@code SmsTemplateRegistry.resolve()}
 * as a <em>literal</em> string — no AI personalization, no per-invoice Stripe pay link, and no
 * paid-guard. The product (AI-drafted, tier-aware copy + a one-touch pay link + an auto-stop on a
 * since-paid invoice) therefore lives here. This stays event-driven (the strategic requirement) and
 * fully controllable. (CF-2's exact rationale, transposed from "no REQUIRE_DEPOSIT action" to "no AI /
 * link / paid-guard in the literal SEND_SMS dispatcher".)
 *
 * <h2>DEFAULT-OFF — blast radius zero</h2>
 * Registered as a {@code @Bean} in {@link ArAutoConfiguration}, which is itself
 * {@code @ConditionalOnProperty(prefix="kmosf.modules.ar", name="enabled", matchIfMissing=false)} — so
 * this subscriber bean does not exist for a non-AR deployment, and the AR-2 sweep that emits the tier
 * events is gated on the same flag, so {@code INVOICE_OVERDUE_*} never fires there either. A non-AR
 * tenant is byte-identical to before this module existed.
 *
 * <h2>Auto-stop on paid / voided (the INVOICE_PAID auto-stop, by construction)</h2>
 * Each tier event is a <strong>one-shot</strong> (the AR-2 {@code DunningLog} fires each (invoice, tier)
 * at most once) — never a scheduled future send — so the auto-stop needs no separate {@code INVOICE_PAID}
 * listener: on every event we <strong>reload the invoice fresh</strong> (tenant-scoped) and skip the
 * send if its status is NOT in {@code {SENT, OVERDUE}} (i.e. it has been PAID / VOIDED / PARTIALLY_PAID
 * since the event was emitted). The since-paid ladder simply goes quiet.
 *
 * <h2>Best-effort + duplicate-safe (never {@code switchIfEmpty(send)})</h2>
 * Every external step (Stripe link, Claude copy, Twilio send) is wrapped {@code onErrorResume}: a Stripe
 * failure degrades to no pay link in the (still-sent) copy via the literal fallback path is avoided —
 * instead, if the pay link can't be minted we still send the literal template carrying whatever link we
 * have (empty link ⇒ skip, see below); a Claude / budget failure degrades to the literal per-tier
 * template; an SMS failure logs and degrades. Idempotency is guaranteed UPSTREAM (the one-shot AR-2
 * {@code DunningLog}), so AR-3 needs no new ledger and the send path is best-effort under a duplicate
 * event delivery. There is <strong>no</strong> {@code switchIfEmpty(create/send)} anywhere.
 *
 * <p>Error codes are reused, not re-allocated: the AI band {@code 1200/1202/1203} (via
 * {@link DunningCopyComposer}), Twilio {@code 2530-2532}, Stripe {@code 2300/2510/3620/3621}. AR-3 mints
 * none of its own (the 4600-4619 band is the sweep's; the defensive-fallback design needs none here).
 */
@Slf4j
public class DunningDispatchService {

    /** The synthetic-context role the async dunning handler runs under. */
    public static final String SYSTEM_ROLE = "AUTOMATION_AR_DUNNING";

    /** A reloaded invoice in one of these statuses is still collectible — the auto-stop allow-set. */
    private static final Set<Invoice.Status> COLLECTIBLE =
            Set.of(Invoice.Status.SENT, Invoice.Status.OVERDUE);

    private final DomainEventPublisher events;
    private final InvoiceRepository invoices;
    private final ContactRepository contacts;
    private final StripeCheckoutService stripeCheckout;
    private final DunningCopyComposer copyComposer;
    private final TwilioSmsService twilioSms;
    private final String brandTone;

    public DunningDispatchService(DomainEventPublisher events,
                                  InvoiceRepository invoices,
                                  ContactRepository contacts,
                                  StripeCheckoutService stripeCheckout,
                                  DunningCopyComposer copyComposer,
                                  TwilioSmsService twilioSms,
                                  String brandTone) {
        this.events = events;
        this.invoices = invoices;
        this.contacts = contacts;
        this.stripeCheckout = stripeCheckout;
        this.copyComposer = copyComposer;
        this.twilioSms = twilioSms;
        this.brandTone = brandTone == null ? "" : brandTone;
    }

    @PostConstruct
    public void subscribe() {
        events.stream()
                .filter(e -> tierForType(e.type()) != null)
                .flatMap(e -> handle(e)
                        .onErrorResume(err -> {
                            log.error("DunningDispatchService: error processing {} for tenant {}",
                                    e.type(), e.tenantId(), err);
                            return Mono.empty();
                        }))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    /**
     * Visible-for-test entry — process a single {@code INVOICE_OVERDUE_*} event end-to-end and return
     * when done (so an IT can drive it deterministically without the live event bus + a sleep). Mirrors
     * {@code RiskTieredPreventionService.handle}.
     */
    public Mono<Void> handle(DomainEvent event) {
        DunningCopyComposer.DunningTier tier = tierForType(event.type());
        if (tier == null) {
            return Mono.empty();
        }
        UUID tenantId = event.tenantId();
        UUID invoiceId = asUuid(event.payload().get("invoiceId"));
        if (invoiceId == null) {
            invoiceId = event.subjectId(); // the AR-2 emit sets subjectId = invoice id
        }
        if (invoiceId == null) {
            return Mono.empty();
        }
        Long daysOverdue = asLong(event.payload().get("daysOverdue"));
        TenantContext ctx = new TenantContext(tenantId, null, Set.of(SYSTEM_ROLE));
        return process(tenantId, invoiceId, tier, daysOverdue)
                .contextWrite(TenantContextHolder.write(ctx));
    }

    private Mono<Void> process(UUID tenantId, UUID invoiceId, DunningCopyComposer.DunningTier tier,
                               Long daysOverdue) {
        // Reload the invoice fresh, tenant-scoped (findByTenantIdAndId is the explicit-tenant finder —
        // the bare findById is NOT auto-scoped). Auto-stop: a since-PAID/VOIDED/PARTIALLY_PAID invoice
        // (status no longer in {SENT, OVERDUE}) is a clean skip — no send.
        return invoices.findByTenantIdAndId(tenantId, invoiceId)
                .flatMap(invoice -> {
                    if (!COLLECTIBLE.contains(invoice.getStatus())) {
                        log.debug("AR-3: invoice {} is {} (no longer collectible) — auto-stop, no dunning send",
                                invoiceId, invoice.getStatus());
                        return Mono.empty();
                    }
                    return resolvePhone(tenantId, invoice)
                            .flatMap(phone -> actOnInvoice(tenantId, invoice, tier, daysOverdue, phone));
                    // No phone ⇒ resolvePhone is empty ⇒ no send (clean skip).
                })
                .then();
    }

    private Mono<Void> actOnInvoice(UUID tenantId, Invoice invoice, DunningCopyComposer.DunningTier tier,
                                    Long daysOverdue, ContactPhone cp) {
        // 1) Mint a one-touch Stripe PAYMENT_LINK (best-effort: a Stripe failure degrades to no link).
        return payLink(invoice.getId())
                .defaultIfEmpty("")
                .flatMap(link -> {
                    if (link.isBlank()) {
                        // No pay link could be minted (e.g. Stripe not connected) — the whole product
                        // value of a dunning text is the one-touch link, so skip the send rather than
                        // text a linkless reminder. The next tier (or a re-run once Stripe is wired) can
                        // pick it up. Best-effort + safe.
                        log.info("AR-3: no Stripe pay link available for invoice {} — skipping {} dunning send",
                                invoice.getId(), tier);
                        return Mono.empty();
                    }
                    return composeThenSend(invoice, tier, daysOverdue, cp, link);
                });
    }

    private Mono<Void> composeThenSend(Invoice invoice, DunningCopyComposer.DunningTier tier,
                                       Long daysOverdue, ContactPhone cp, String payLink) {
        BigDecimal amountDue = invoice.getBalance() != null ? invoice.getBalance() : invoice.getTotal();
        DunningCopyComposer.DunningContext context = new DunningCopyComposer.DunningContext(
                tier, cp.firstName(), invoice.getInvoiceNumber(), amountDue, invoice.getCurrency(),
                daysOverdue, payLink, brandTone);

        // 2) Compose the on-brand, budget-gated copy (best-effort → literal per-tier fallback).
        return copyComposer.compose(context)
                .onErrorResume(err -> {
                    log.info("AR-3: dunning personalization unavailable for invoice {} "
                            + "(falling back to literal template): {}", invoice.getId(), err.toString());
                    return Mono.just(DunningCopyComposer.fallbackBody(context));
                })
                // 3) Send the single SMS (best-effort) and 4) emit the advisory DUNNING_SENT.
                .flatMap(body -> sendSms(cp.phone(), body)
                        .then(Mono.fromRunnable(() -> emitDunningSent(
                                invoice.getTenantId(), invoice.getId(), invoice.getContactId(), tier))));
    }

    /** One-touch Stripe payment link, best-effort. A Stripe failure (not-connected / upstream) → empty. */
    private Mono<String> payLink(UUID invoiceId) {
        return stripeCheckout.createCheckoutForInvoice(invoiceId, StripeCheckoutService.Mode.PAYMENT_LINK)
                .map(StripeCheckoutService.CheckoutResult::url)
                .onErrorResume(err -> {
                    log.warn("AR-3: Stripe pay-link creation failed for invoice {} (continuing): {}",
                            invoiceId, err.toString());
                    return Mono.empty();
                });
    }

    private Mono<Void> sendSms(String phone, String body) {
        SmsCommunicationRequest req = SmsCommunicationRequest.builder()
                .to(new PhoneContact(phone))
                .body(body)
                .build();
        return twilioSms.sendSms(req)
                .doOnSuccess(ok -> log.debug("AR-3: dunning SMS sent to {}", phone))
                .onErrorResume(err -> {
                    // Best-effort: no Twilio connection (2530-2532) or a send failure must not abort the
                    // stream — log + degrade.
                    log.warn("AR-3: dunning SMS send failed for {} (continuing): {}", phone, err.toString());
                    return Mono.just(false);
                })
                .then();
    }

    /**
     * Resolve the invoice's contact phone (+ first name for the greeting) from {@code invoice.contactId}
     * → {@code Contact.phones[0].number} (the {@code CoverageNudgeJob.resolvePhone}/{@code firstPhone}
     * pattern). An invoice with no contact, no contact record, or no phone yields empty ⇒ no send.
     */
    private Mono<ContactPhone> resolvePhone(UUID tenantId, Invoice invoice) {
        UUID contactId = invoice.getContactId();
        if (contactId == null) {
            return Mono.empty();
        }
        return contacts.findByTenantIdAndId(tenantId, contactId)
                .mapNotNull(contact -> {
                    String phone = firstPhone(contact);
                    return phone == null ? null : new ContactPhone(phone, contact.getFirstName());
                });
    }

    private String firstPhone(Contact contact) {
        List<PhoneNumber> phones = contact.getPhones();
        if (phones == null || phones.isEmpty()) {
            return null;
        }
        for (PhoneNumber p : phones) {
            if (p != null && p.number() != null && !p.number().isBlank()) {
                return p.number();
            }
        }
        return null;
    }

    private void emitDunningSent(UUID tenantId, UUID invoiceId, UUID contactId,
                                 DunningCopyComposer.DunningTier tier) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("invoiceId", invoiceId.toString());
        if (contactId != null) {
            payload.put("contactId", contactId.toString());
        }
        payload.put("tier", tier.name());
        payload.put("channel", "sms");
        events.publish(DomainEvent.of(DomainEventType.DUNNING_SENT, tenantId, invoiceId, payload));
    }

    /** The escalating tier the given event type carries, or null if it's not a dunning tier event. */
    private static DunningCopyComposer.DunningTier tierForType(String type) {
        if (DomainEventType.INVOICE_OVERDUE_D3.equals(type)) return DunningCopyComposer.DunningTier.D3;
        if (DomainEventType.INVOICE_OVERDUE_D7.equals(type)) return DunningCopyComposer.DunningTier.D7;
        if (DomainEventType.INVOICE_OVERDUE_D14.equals(type)) return DunningCopyComposer.DunningTier.D14;
        return null;
    }

    private static UUID asUuid(Object raw) {
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s) {
            try {
                return UUID.fromString(s);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
        return null;
    }

    private static Long asLong(Object raw) {
        if (raw instanceof Number n) return n.longValue();
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    /** Internal carrier for a resolved contact phone + first name. */
    private record ContactPhone(String phone, String firstName) {
    }
}
