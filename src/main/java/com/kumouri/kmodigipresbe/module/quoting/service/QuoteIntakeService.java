package com.kumouri.kmodigipresbe.module.quoting.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.quoting.model.AttributeSource;
import com.kumouri.kmodigipresbe.module.quoting.model.JobKind;
import com.kumouri.kmodigipresbe.module.quoting.model.PriceBook;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteAttributes;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRange;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteRequest;
import com.kumouri.kmodigipresbe.module.quoting.model.QuoteStatus;
import com.kumouri.kmodigipresbe.module.quoting.model.RepairVsReplace;
import com.kumouri.kmodigipresbe.module.quoting.repository.PriceBookRepository;
import com.kumouri.kmodigipresbe.module.quoting.repository.QuoteRequestRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * T8 (Home Services "QuoteNow") — the Q4 orchestrator: turns a homeowner's public submission into a
 * persisted {@link QuoteRequest} with a price range + a repair-vs-replace recommendation. The
 * <strong>vision-COMPOSITION</strong> heart of QuoteNow — it composes the reused vision spine
 * ({@link QuoteVisionService} → {@code AiVisionService.extract}) with the price-book synthesis
 * ({@link QuoteSynthesisService}) and the reasoner ({@link RepairVsReplaceReasoner}).
 *
 * <h2>Auth — the widget token, tenant from the token ONLY</h2>
 * The path {@code {token}} is a {@link PublicWidgetTokenService} HMAC token (reused verbatim — the
 * {@code ServiceRequestWidgetController} precedent; QuoteNow binds no entity, so the 3-field
 * tenant-only token is exactly right and that core stays empty-diff). A wrong {@code widgetType} →
 * {@code 4430}/401 (the {@code EquipmentVisionService} 4210 / home-services 2700 posture); generic
 * token rejections surface {@code 1600-1603}. The tenant comes from the token claim <strong>only</strong>
 * — never the form fields — so a stranger cannot burn another tenant's AI budget. All effects run
 * under a synthetic {@code TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"))}.
 *
 * <h2>Vision is best-effort; the quote is ALWAYS produced</h2>
 * With a photo: {@link QuoteVisionService#readFromPhoto} (best-effort) reads attributes; they are
 * merged with any homeowner-typed manual attributes (manual wins where present). Without a photo (or
 * on a vision failure): the manual attributes alone. {@link QuoteSynthesisService} then synthesizes a
 * repair + a replace range (nothing priceable → a diagnostic-visit range), and the reasoner decides.
 * The quote is persisted regardless — AI is triage, not truth.
 *
 * <h2>Lead</h2>
 * The homeowner is found-or-created as a {@code Contact} by phone → email (explicit-boolean, the
 * {@code ServiceRequestWidgetController} / {@code TwilioVoicemailService} precedent — an existing
 * contact is reused untouched; never {@code switchIfEmpty(create)} for the find-or-create). The
 * {@code QuoteRequest} links the contact + the stored photo attachment.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code QuotingAutoConfiguration} when the module is on.
 */
@Slf4j
public class QuoteIntakeService {

    /** The widgetType a quote-intake token must carry. */
    public static final String WIDGET_TYPE = "quote-intake";

    private final PublicWidgetTokenService tokens;
    private final PriceBookRepository priceBooks;
    private final QuoteRequestRepository quotes;
    private final ContactRepository contacts;
    private final QuoteVisionService quoteVisionService;
    private final QuoteSynthesisService synthesis;
    private final RepairVsReplaceReasoner reasoner;
    private final DomainEventPublisher events;

    public QuoteIntakeService(PublicWidgetTokenService tokens,
                              PriceBookRepository priceBooks,
                              QuoteRequestRepository quotes,
                              ContactRepository contacts,
                              QuoteVisionService quoteVisionService,
                              QuoteSynthesisService synthesis,
                              RepairVsReplaceReasoner reasoner,
                              DomainEventPublisher events) {
        this.tokens = tokens;
        this.priceBooks = priceBooks;
        this.quotes = quotes;
        this.contacts = contacts;
        this.quoteVisionService = quoteVisionService;
        this.synthesis = synthesis;
        this.reasoner = reasoner;
        this.events = events;
    }

    /** The homeowner's typed manual inputs from the intake form (all optional). */
    public record ManualInput(String equipmentType, String brand, Integer ageYears, String failureMode,
                              String problemDescription, String phone, String email, String name) {

        QuoteAttributes toManualAttributes() {
            return QuoteAttributes.builder()
                    .equipmentType(equipmentType)
                    .brand(brand)
                    .ageYears(ageYears)
                    .failureMode(failureMode)
                    .source(AttributeSource.MANUAL)
                    .confidence(1.0)
                    .build();
        }
    }

    /**
     * Submit a quote. Verify the token, establish the synthetic tenant context, optionally read the
     * photo, merge attributes, synthesize the range + recommendation, find-or-create the homeowner
     * Contact, persist the {@link QuoteRequest}, and emit the advisory event. {@code imageBytes} may
     * be null (the manual-only path). Never blocks the quote on a vision failure.
     */
    public Mono<QuoteRequest> submit(String token, byte[] imageBytes, String mediaType,
                                     String filename, ManualInput input) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<QuoteRequest>error(new DigiPresBeException(
                                "Quote-intake token type mismatch (expected '" + WIDGET_TYPE
                                        + "', got '" + claims.widgetType() + "')", 4430, 401));
                    }
                    UUID tenantId = claims.tenantId();
                    TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
                    return runPipeline(tenantId, claims, imageBytes, mediaType, filename, input)
                            .contextWrite(TenantContextHolder.write(anon));
                });
    }

    private Mono<QuoteRequest> runPipeline(UUID tenantId, PublicWidgetToken claims, byte[] imageBytes,
                                           String mediaType, String filename, ManualInput input) {
        ManualInput in = input == null
                ? new ManualInput(null, null, null, null, null, null, null, null) : input;
        QuoteAttributes manual = in.toManualAttributes();

        Mono<VisionStep> visionStep = (imageBytes != null && imageBytes.length > 0)
                ? quoteVisionService.readFromPhoto(tenantId, imageBytes, mediaType, filename)
                        .map(vr -> new VisionStep(vr.attachmentId(), vr.attributes()))
                : Mono.just(new VisionStep(null, null));

        return Mono.zip(visionStep, loadBook(tenantId))
                .flatMap(tuple -> {
                    VisionStep vs = tuple.getT1();
                    PriceBook book = tuple.getT2().orElse(null);

                    QuoteAttributes attributes = resolveAttributes(vs.visionAttributes(), manual);
                    QuoteRange repairRange = synthesis.synthesizeFor(book, attributes, JobKind.REPAIR);
                    QuoteRange replaceRange = synthesis.synthesizeFor(book, attributes, JobKind.REPLACE);
                    QuoteRange headline = synthesis.synthesize(book, attributes);
                    RepairVsReplace rvr = reasoner.decide(book, attributes, repairRange, replaceRange);

                    return findOrCreateContact(tenantId, in)
                            .flatMap(contact -> persistQuote(tenantId, contact, vs.attachmentId(),
                                    attributes, headline, rvr, in))
                            .flatMap(saved -> Mono.fromRunnable(() -> emitRequested(tenantId, saved))
                                    .thenReturn(saved));
                });
    }

    /** Merge vision + manual: manual wins where present; null vision → manual alone. */
    private static QuoteAttributes resolveAttributes(QuoteAttributes vision, QuoteAttributes manual) {
        if (vision == null || vision.isEmpty()) {
            return manual;
        }
        return vision.mergedWithManual(manual);
    }

    private Mono<java.util.Optional<PriceBook>> loadBook(UUID tenantId) {
        return priceBooks.findByTenantId(tenantId)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty());
    }

    private Mono<QuoteRequest> persistQuote(UUID tenantId, Contact contact, UUID attachmentId,
                                            QuoteAttributes attributes, QuoteRange range,
                                            RepairVsReplace rvr, ManualInput in) {
        QuoteRequest quote = QuoteRequest.builder()
                .tenantId(tenantId)
                .contactId(contact == null ? null : contact.getId())
                .contactPhone(trimToNull(in.phone()))
                .contactEmail(trimToNull(in.email()))
                .problemDescription(trimToNull(in.problemDescription()))
                .attributes(attributes)
                .range(range)
                .repairVsReplace(rvr)
                .photoAttachmentId(attachmentId)
                .status(QuoteStatus.NEW)
                .build();
        return quotes.save(quote);
    }

    /**
     * Find-or-create the homeowner Contact by phone → email (explicit-boolean; an existing contact is
     * reused untouched — never {@code switchIfEmpty(create)} for the find-or-create branch; the
     * {@code ServiceRequestWidgetController} precedent). Returns null-safe: with no phone/email a fresh
     * anonymous contact is created so the quote always links to a contact.
     */
    private Mono<Contact> findOrCreateContact(UUID tenantId, ManualInput in) {
        String phone = trimToNull(in.phone());
        String email = trimToNull(in.email());
        Mono<Contact> byPhone = phone == null
                ? Mono.empty()
                : contacts.findByTenantAndPhoneNumber(tenantId, phone).next();
        Mono<Contact> byEmail = email == null
                ? Mono.empty()
                : contacts.findByTenantAndEmailAddress(tenantId, email).next();

        // Find-fallback phone -> email (switchIfEmpty for a FIND is fine), then explicit-boolean
        // create — NEVER switchIfEmpty(create) (the §9 invariant + the ServiceRequestWidget precedent;
        // matches this method's own Javadoc).
        return byPhone
                .switchIfEmpty(byEmail)
                .map(java.util.Optional::of)
                .defaultIfEmpty(java.util.Optional.empty())
                .flatMap(existing -> existing.isPresent()
                        ? Mono.just(existing.get())
                        : contacts.save(buildContact(tenantId, in)));
    }

    private Contact buildContact(UUID tenantId, ManualInput in) {
        String phone = trimToNull(in.phone());
        String email = trimToNull(in.email());
        String name = trimToNull(in.name());
        List<PhoneNumber> phones = new ArrayList<>();
        if (phone != null) {
            phones.add(PhoneNumber.builder().number(phone).label("quote").build());
        }
        List<EmailContact> emails = new ArrayList<>();
        if (email != null) {
            emails.add(new EmailContact(email));
        }
        String displayName = name != null ? name
                : (phone != null ? "Quote request " + phone
                : (email != null ? email : "Quote request"));
        return Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName(displayName)
                .phones(phones)
                .emails(emails)
                .tags(Set.of("quotenow-lead"))
                .build();
    }

    private void emitRequested(UUID tenantId, QuoteRequest quote) {
        Map<String, Object> payload = new HashMap<>();
        if (quote.getId() != null) payload.put("quoteRequestId", quote.getId().toString());
        if (quote.getContactId() != null) payload.put("contactId", quote.getContactId().toString());
        if (quote.getAttributes() != null && quote.getAttributes().getEquipmentType() != null) {
            payload.put("equipmentType", quote.getAttributes().getEquipmentType());
        }
        if (quote.getRepairVsReplace() != null && quote.getRepairVsReplace().getRecommendation() != null) {
            payload.put("recommendation", quote.getRepairVsReplace().getRecommendation().name());
        }
        payload.put("diagnosticOnly", quote.getRange() != null && quote.getRange().isDiagnosticOnly());
        events.publish(DomainEvent.of(
                DomainEventType.QUOTE_REQUESTED, tenantId, quote.getId(), payload));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** The stored photo attachment id (nullable) + the vision-read attributes (nullable). */
    private record VisionStep(UUID attachmentId, QuoteAttributes visionAttributes) {
    }
}
