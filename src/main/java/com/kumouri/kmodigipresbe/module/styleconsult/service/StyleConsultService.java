package com.kumouri.kmodigipresbe.module.styleconsult.service;

import com.kumouri.kmodigipresbe.automation.DomainEvent;
import com.kumouri.kmodigipresbe.automation.DomainEventPublisher;
import com.kumouri.kmodigipresbe.automation.DomainEventType;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributeSource;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleAttributes;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsult;
import com.kumouri.kmodigipresbe.module.styleconsult.model.StyleConsultStatus;
import com.kumouri.kmodigipresbe.module.styleconsult.repository.StyleConsultRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * T9 (Salon "StyleConsult AI") — the S1 orchestrator: turns a prospect's public inspiration-photo
 * submission into a persisted {@link StyleConsult} with service + <strong>margin-aware retail</strong>
 * recommendations. The <strong>vision-COMPOSITION</strong> heart of StyleConsult — it composes the
 * reused vision spine ({@link StyleConsultVisionService} → {@code AiVisionService.extract}) with the
 * pure recommendation engine ({@link StyleRecommendationService}). The salon-flavored twin of the T8
 * {@code QuoteIntakeService}.
 *
 * <h2>Auth — the widget token, tenant from the token ONLY</h2>
 * The path {@code {token}} is a {@link PublicWidgetTokenService} HMAC token (reused verbatim — the
 * {@code ServiceRequestWidgetController}/T8 precedent; StyleConsult binds no entity, so the 3-field
 * tenant-only token is exactly right and that core stays empty-diff). A wrong {@code widgetType} →
 * {@code 4450}/401 (the T8 {@code 4430} posture); generic token rejections surface {@code 1600-1603}.
 * The tenant comes from the token claim <strong>only</strong> — never the form fields — so a stranger
 * cannot burn another tenant's AI budget. All effects run under a synthetic
 * {@code TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"))}.
 *
 * <h2>Vision is best-effort; the consult is ALWAYS produced</h2>
 * With a photo: {@link StyleConsultVisionService#readFromPhoto} (best-effort) reads style attributes;
 * they are merged with any prospect-typed manual attributes (manual wins where present). Without a
 * photo (or on a vision failure): the manual attributes alone. {@link StyleRecommendationService} then
 * composes the service + margin-aware retail recommendations. The consult is persisted regardless — AI
 * is triage, not truth; a human stylist confirms (the {@link StyleRecommendationService#STYLIST_CONFIRM_NOTE}).
 *
 * <h2>Lead</h2>
 * The prospect is found-or-created as a {@code Contact} by phone → email (explicit-boolean, the
 * {@code QuoteIntakeService}/{@code ServiceRequestWidgetController} precedent — an existing contact is
 * reused untouched; never {@code switchIfEmpty(create)} for the find-or-create). The {@code StyleConsult}
 * links the contact + the stored photo attachment.
 *
 * <p>Hand-constructed as a {@code @Bean} by {@code StyleConsultAutoConfiguration} when the salon
 * flagship is on.
 */
@Slf4j
public class StyleConsultService {

    /** The widgetType a style-consult token must carry. */
    public static final String WIDGET_TYPE = "style-consult";

    private final PublicWidgetTokenService tokens;
    private final StyleConsultRepository consults;
    private final ContactRepository contacts;
    private final StyleConsultVisionService visionService;
    private final StyleRecommendationService recommendationService;
    private final DomainEventPublisher events;

    public StyleConsultService(PublicWidgetTokenService tokens,
                               StyleConsultRepository consults,
                               ContactRepository contacts,
                               StyleConsultVisionService visionService,
                               StyleRecommendationService recommendationService,
                               DomainEventPublisher events) {
        this.tokens = tokens;
        this.consults = consults;
        this.contacts = contacts;
        this.visionService = visionService;
        this.recommendationService = recommendationService;
        this.events = events;
    }

    /** The prospect's typed manual inputs from the intake form (all optional). */
    public record ManualInput(String styleCategory, String length, String texture, String color,
                              String notes, String phone, String email, String name) {

        StyleAttributes toManualAttributes() {
            return StyleAttributes.builder()
                    .styleCategory(styleCategory)
                    .length(length)
                    .texture(texture)
                    .color(color)
                    .source(StyleAttributeSource.MANUAL)
                    .confidence(1.0)
                    .build();
        }
    }

    /**
     * Submit a style consult. Verify the token, establish the synthetic tenant context, optionally read
     * the inspiration photo, merge attributes, compose the service + margin-aware retail
     * recommendations, find-or-create the prospect Contact, persist the {@link StyleConsult}, and emit
     * the advisory event. {@code imageBytes} may be null (the manual-only path). Never blocks the
     * consult on a vision failure.
     */
    public Mono<StyleConsult> submit(String token, byte[] imageBytes, String mediaType,
                                     String filename, ManualInput input) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> {
                    if (!WIDGET_TYPE.equals(claims.widgetType())) {
                        return Mono.<StyleConsult>error(new DigiPresBeException(
                                "Style-consult token type mismatch (expected '" + WIDGET_TYPE
                                        + "', got '" + claims.widgetType() + "')", 4450, 401));
                    }
                    UUID tenantId = claims.tenantId();
                    TenantContext anon = new TenantContext(tenantId, null, Set.of("PUBLIC_WIDGET"));
                    return runPipeline(tenantId, imageBytes, mediaType, filename, input)
                            .contextWrite(TenantContextHolder.write(anon));
                });
    }

    private Mono<StyleConsult> runPipeline(UUID tenantId, byte[] imageBytes, String mediaType,
                                           String filename, ManualInput input) {
        ManualInput in = input == null
                ? new ManualInput(null, null, null, null, null, null, null, null) : input;
        StyleAttributes manual = in.toManualAttributes();

        Mono<VisionStep> visionStep = (imageBytes != null && imageBytes.length > 0)
                ? visionService.readFromPhoto(tenantId, imageBytes, mediaType, filename)
                        .map(vr -> new VisionStep(vr.attachmentId(), vr.attributes()))
                : Mono.just(new VisionStep(null, null));

        return visionStep.flatMap(vs -> {
            StyleAttributes attributes = resolveAttributes(vs.visionAttributes(), manual);
            return recommendationService.recommend(tenantId, attributes)
                    .flatMap(recs -> findOrCreateContact(tenantId, in)
                            .flatMap(contact -> persistConsult(tenantId, contact, vs.attachmentId(),
                                    attributes, recs, in))
                            .flatMap(saved -> Mono.fromRunnable(() -> emitRequested(tenantId, saved))
                                    .thenReturn(saved)));
        });
    }

    /** Merge vision + manual: manual wins where present; null vision → manual alone. */
    private static StyleAttributes resolveAttributes(StyleAttributes vision, StyleAttributes manual) {
        if (vision == null || vision.isEmpty()) {
            return manual;
        }
        return vision.mergedWithManual(manual);
    }

    private Mono<StyleConsult> persistConsult(UUID tenantId, Contact contact, UUID attachmentId,
                                              StyleAttributes attributes,
                                              StyleRecommendationService.Recommendations recs,
                                              ManualInput in) {
        StyleConsult consult = StyleConsult.builder()
                .tenantId(tenantId)
                .contactId(contact == null ? null : contact.getId())
                .contactPhone(trimToNull(in.phone()))
                .contactEmail(trimToNull(in.email()))
                .notes(trimToNull(in.notes()))
                .attributes(attributes)
                .serviceRecommendations(recs.services())
                .retailRecommendations(recs.retail())
                .photoAttachmentId(attachmentId)
                .status(StyleConsultStatus.NEW)
                .build();
        return consults.save(consult);
    }

    /**
     * Find-or-create the prospect Contact by phone → email (explicit-boolean; an existing contact is
     * reused untouched — never {@code switchIfEmpty(create)} for the find-or-create branch; the
     * {@code QuoteIntakeService} precedent). Returns null-safe: with no phone/email a fresh anonymous
     * contact is created so the consult always links to a contact.
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
        // create — NEVER switchIfEmpty(create) (the §9 invariant + the QuoteIntakeService precedent).
        return byPhone
                .switchIfEmpty(byEmail)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
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
            phones.add(PhoneNumber.builder().number(phone).label("consult").build());
        }
        List<EmailContact> emails = new ArrayList<>();
        if (email != null) {
            emails.add(new EmailContact(email));
        }
        String displayName = name != null ? name
                : (phone != null ? "Style consult " + phone
                : (email != null ? email : "Style consult"));
        return Contact.builder()
                .tenantId(tenantId)
                .type(ContactType.PERSON)
                .displayName(displayName)
                .phones(phones)
                .emails(emails)
                .tags(Set.of("styleconsult-lead"))
                .build();
    }

    private void emitRequested(UUID tenantId, StyleConsult consult) {
        Map<String, Object> payload = new HashMap<>();
        if (consult.getId() != null) payload.put("styleConsultId", consult.getId().toString());
        if (consult.getContactId() != null) payload.put("contactId", consult.getContactId().toString());
        if (consult.getAttributes() != null && consult.getAttributes().getStyleCategory() != null) {
            payload.put("styleCategory", consult.getAttributes().getStyleCategory());
        }
        payload.put("serviceCount", consult.getServiceRecommendations() == null
                ? 0 : consult.getServiceRecommendations().size());
        payload.put("retailCount", consult.getRetailRecommendations() == null
                ? 0 : consult.getRetailRecommendations().size());
        events.publish(DomainEvent.of(
                DomainEventType.STYLE_CONSULT_REQUESTED, tenantId, consult.getId(), payload));
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** The stored photo attachment id (nullable) + the vision-read attributes (nullable). */
    private record VisionStep(UUID attachmentId, StyleAttributes visionAttributes) {
    }
}
