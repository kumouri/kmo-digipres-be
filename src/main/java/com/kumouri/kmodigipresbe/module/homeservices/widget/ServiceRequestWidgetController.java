package com.kumouri.kmodigipresbe.module.homeservices.widget;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.contact.Contact;
import com.kumouri.kmodigipresbe.model.contact.ContactType;
import com.kumouri.kmodigipresbe.model.contact.EmailContact;
import com.kumouri.kmodigipresbe.model.contact.PhoneNumber;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrder;
import com.kumouri.kmodigipresbe.module.fieldservice.model.WorkOrderStatus;
import com.kumouri.kmodigipresbe.module.fieldservice.repository.WorkOrderRepository;
import com.kumouri.kmodigipresbe.repository.ContactRepository;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetToken;
import com.kumouri.kmodigipresbe.service.widget.PublicWidgetTokenService;
import com.kumouri.kmodigipresbe.tenancy.TenantContext;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Phase 10e — anonymous public widget endpoint for "request service" form
 * submissions. The token in the URL path proves which tenant issued the widget
 * snippet; the {@code PublicWidgetSecurityConfig} chain (Phase 9b,
 * {@code @Order(-3)}) matches {@code /public/widget/**} and permits all
 * exchanges, so this controller does all the auth work via
 * {@link PublicWidgetTokenService#verify}.
 *
 * <p>Flow:
 * <ol>
 *   <li>Verify the token, asserting {@code widgetType="service-request"}
 *       (mismatched widget types reject with errorCode {@code 2700}, status
 *       401). Bad signatures, expirations, and tampering surface from the
 *       token service with errorCodes {@code 1600-1603}.</li>
 *   <li>Establish a synthetic {@link TenantContext} carrying the token's
 *       tenant id and a {@code PUBLIC_WIDGET} role — needed so the
 *       {@code TenantStampingCallback} stamps Contact + WorkOrder with the
 *       right tenant.</li>
 *   <li>Upsert the Contact by email + tenant. Existing contacts are touched
 *       (the lookup is read-only; we don't rewrite an existing contact's name
 *       from a public form to avoid letting strangers mutate staff-curated
 *       data).</li>
 *   <li>Create a DRAFT WorkOrder linked to the resolved JobSite (if the
 *       submission carries one) or with a null jobSite for staff triage.</li>
 *   <li>Respond with both IDs.</li>
 * </ol>
 *
 * <p>Module gating is intentionally <em>looser</em> than the staff controllers:
 * because this endpoint is anonymous and the token already binds tenant +
 * widget-type, we skip the per-handler {@code modules.requireEnabled} check —
 * that check needs an authenticated request to read the tenant's enabled-modules
 * set, and the token issuance path (admin-only, see
 * {@link ServiceRequestTokenIssuer}) already enforces the home-services gate.
 * A token issued before the tenant disabled home-services therefore continues
 * to accept submissions until expiry; the assumption is that admins rotate
 * widget tokens when toggling modules off.
 *
 * <p>The {@code @ConditionalOnProperty} on the controller itself means a server
 * with {@code kmosf.modules.home-services.enabled=false} does not register the
 * endpoint at all — tokens issued by a now-decommissioned server will get a 404
 * routing-level rejection rather than a 401, which is the correct outcome.
 */
@Slf4j
@RestController
@RequestMapping("/public/widget/service-request")
@ConditionalOnProperty(prefix = "kmosf.modules.home-services", name = "enabled")
@RequiredArgsConstructor
public class ServiceRequestWidgetController {

    public static final String WIDGET_TYPE = "service-request";

    private final PublicWidgetTokenService tokens;
    private final ContactRepository contacts;
    private final WorkOrderRepository workOrders;

    @PostMapping("/{token}")
    public Mono<ServiceRequestSubmissionResponseDTO> submit(
            @PathVariable String token,
            @Valid @RequestBody ServiceRequestSubmissionDTO body) {
        return Mono.fromCallable(() -> tokens.verify(token))
                .flatMap(claims -> handleSubmission(claims, body));
    }

    private Mono<ServiceRequestSubmissionResponseDTO> handleSubmission(
            PublicWidgetToken claims, ServiceRequestSubmissionDTO body) {
        if (!WIDGET_TYPE.equals(claims.widgetType())) {
            return Mono.error(new DigiPresBeException(
                    "Widget token type mismatch (expected '" + WIDGET_TYPE
                            + "', got '" + claims.widgetType() + "')",
                    2700, 401));
        }
        TenantContext anon = new TenantContext(
                claims.tenantId(), null, Set.of("PUBLIC_WIDGET"));
        return upsertContact(body)
                .flatMap(contact -> createDraftWorkOrder(contact, body)
                        .map(wo -> new ServiceRequestSubmissionResponseDTO(
                                contact.getId(), wo.getId())))
                .contextWrite(TenantContextHolder.write(anon));
    }

    /**
     * Inline upsert-by-email: matches the BookingService pattern. The
     * {@code ContactRepository.findByTenantAndEmailAddress} query is already
     * tenant-scoped, so the lookup is safe even though we're inside an
     * anonymous chain.
     */
    private Mono<Contact> upsertContact(ServiceRequestSubmissionDTO body) {
        return TenantContextHolder.required().flatMap(ctx ->
                contacts.findByTenantAndEmailAddress(ctx.tenantId(), body.email())
                        .next()
                        .switchIfEmpty(Mono.defer(() -> contacts.save(buildContact(body)))));
    }

    private Mono<WorkOrder> createDraftWorkOrder(Contact contact, ServiceRequestSubmissionDTO body) {
        WorkOrder wo = WorkOrder.builder()
                .jobSiteId(body.jobSiteId())
                .status(WorkOrderStatus.DRAFT)
                .serviceType(body.serviceType())
                .notes(annotateNotes(contact, body))
                .build();
        return workOrders.save(wo);
    }

    private Contact buildContact(ServiceRequestSubmissionDTO body) {
        String fn = trimToNull(body.firstName());
        String ln = trimToNull(body.lastName());
        String displayName;
        if (fn == null && ln == null) {
            displayName = body.email();
        } else {
            displayName = ((fn == null ? "" : fn) + " " + (ln == null ? "" : ln)).trim();
        }
        List<PhoneNumber> phones = new ArrayList<>();
        if (trimToNull(body.phone()) != null) {
            phones.add(PhoneNumber.builder().number(body.phone().trim()).build());
        }
        return Contact.builder()
                .type(ContactType.PERSON)
                .firstName(fn)
                .lastName(ln)
                .displayName(displayName)
                .emails(List.of(new EmailContact(body.email())))
                .phones(phones)
                .tags(Set.of("public-service-request"))
                .build();
    }

    /**
     * Prepends a "received via widget" marker so triaging staff can see the
     * submission origin at a glance without having to chase tags or activity
     * timelines. Keeps the original {@code body.notes()} text intact.
     */
    private static String annotateNotes(Contact contact, ServiceRequestSubmissionDTO body) {
        StringBuilder sb = new StringBuilder();
        sb.append("Service request submitted via public widget");
        sb.append(" (contactId=").append(contact.getId()).append(")");
        if (body.notes() != null && !body.notes().isBlank()) {
            sb.append("\n\n").append(body.notes());
        }
        return sb.toString();
    }

    private static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }
}
