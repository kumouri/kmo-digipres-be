package com.kumouri.kmodigipresbe.controller.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.reactive.error.ErrorWebExceptionHandler;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.lang.NonNull;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.net.URI;
import java.util.UUID;

/**
 * Translates exceptions to RFC 7807 {@link ProblemDetail} responses. Ordered
 * {@code -2} so it beats the default Spring resource handler.
 *
 * <h2>Error code allocation</h2>
 * Each subsystem owns a numeric range so the {@code errorCode} stays mnemonic. Ranges
 * are assigned as they're first used; the table below reflects what's in use plus
 * Phase 9 reservations.
 * <ul>
 *   <li>{@code 1001-1099} — Tenancy (no tenant context, foreign tenantId, JWT issues)</li>
 *   <li>{@code 1100-1199} — Core entity not-found, custom field validation, module gating</li>
 *   <li>{@code 1200-1299} — <em>Phase 9f reserved</em>: AI budget violations + AI service
 *       failures. Re-allocated from the plan §8 slot of 1300-1399 because Phase 3's
 *       {@code Rfc5545RecurringSchedule} ships {@code 1300} for "Invalid RRULE".</li>
 *   <li>{@code 1300} — Invalid RRULE (Phase 3, now in {@code Rfc5545RecurringSchedule})</li>
 *   <li>{@code 1400-1499} — Deal validation (1400 not-found, 1401 lostReason required)</li>
 *   <li>{@code 1500-1599} — <em>Phase 13 reserved</em>: mobile sync conflicts</li>
 *   <li>{@code 1600-1699} — <em>Phase 9b</em>: public widget rejections (1600 generic;
 *       1601 malformed; 1602 signature invalid; 1603 expired)</li>
 *   <li>{@code 1700-1799} — <em>Phase 9c</em>: transactional email + Postmark webhook
 *       (1700 send failure, 1701 no token, 1702 not connected, 1703 secret missing,
 *       1704 auth invalid, 1705 body not JSON, 1706 path tenant invalid)</li>
 *   <li>{@code 1800-1899} — <em>Phase 9a</em>: audit/compliance (RoleGuard 1800,
 *       audit query param validation 1801). The original plan §8 listed 1400-1499 for
 *       audit/compliance, but {@code DealCrudService} ships 1400/1401 since Phase 1 —
 *       audit codes were shifted to 1800-1899 to avoid renumbering merged code.</li>
 *   <li>{@code 1900-1999} — Phase 6 automation (WorkflowRule 1900, WebhookSubscription
 *       1910). The plan's original AI slot would have collided here too.</li>
 *   <li>{@code 2200-2399} — <em>Phase 7</em>: Quotes + Invoices (core billing).
 *       {@code 2200} Quote not found (404); {@code 2201} quote PDF storageRef not owned
 *       by tenant (403); {@code 2300} Invoice not found (404); {@code 2301} invoice must
 *       be created as DRAFT — a non-DRAFT status on {@code POST /invoices} is rejected
 *       (400) because {@code create} is the DRAFT seam (it neither numbers nor emits
 *       {@code INVOICE_FINALIZED}); issue via {@code POST /invoices/{id}/status};
 *       {@code 2310} source quote not found on {@code POST /invoices/from-quote/{quoteId}}
 *       (404).</li>
 *   <li>{@code 2700-2799} — <em>Phase 10</em>: home-services. 10c equipment
 *       {@code 2730} not-found (admin delete falls through to the shared
 *       {@code RoleGuard} 1800 in the Phase 9a audit/compliance range). 10e
 *       SMS automation + service-request widget: {@code 2700} widget-type
 *       mismatch (token's {@code widgetType} claim is not "service-request");
 *       {@code 2701} widget token rejected (specific home-services token
 *       failures distinct from the generic 1600-range token rejections);
 *       {@code 2702} service-request DTO invalid (handled via bean-validation's
 *       WebExchangeBindException today, reserved for custom cross-field
 *       checks); {@code 2710} SMS dispatch failed (passthrough from Twilio
 *       when the {@code SEND_SMS} dispatcher cannot reach Twilio at all).</li>
 *   <li>{@code 2900-2999} — <em>Phase 13b/13c</em>: Service Hub. {@code 2900} invalid
 *       ticket status transition; {@code 2901} ticket not found; {@code 2902} SLA policy
 *       not found; {@code 2910} SLA policy name duplicate; {@code 2920} KB article not
 *       found; {@code 2921} KB article slug duplicate; {@code 2922} cannot publish empty
 *       article.</li>
 *   <li>{@code 2800-2899} — <em>Phase 10d</em>: QuickBooks Online. {@code 2800}
 *       no connection / missing realmId or accessToken on connection; {@code 2801}
 *       token exchange or refresh failed; {@code 2802} invoice push failed
 *       (mapped from any non-2xx response or downstream throwable);
 *       {@code 2810} OAuth state missing / expired / malformed (covers the
 *       callback's bad-state path and the webhook controller's malformed-path
 *       cases); {@code 2811} webhook fired for a tenant with no QBO connection;
 *       {@code 2812} webhook signature (HMAC of body) did not match the tenant's
 *       stored {@code webhookVerifierToken}, also reused for invalid OAuth
 *       {@code state} signatures; {@code 2813} webhook payload malformed
 *       (non-JSON or missing required fields).</li>
 *   <li>{@code 2900-2999} — <em>Phase 12</em>: salon/spa module. {@code 2900}
 *       service-menu item not found or not eligible for the requested staff member;
 *       {@code 2901} staff member not available in the requested time window;
 *       {@code 2902} booking cancelled outside the cancellation policy window;
 *       {@code 2910} loyalty account not found for a contact;
 *       {@code 2911} widget-type mismatch on the salon-booking widget token.</li>
 *   <li>{@code 3000-3099} — <em>Phase 12d</em>: Square POS integration.
 *       {@code 3000} webhook signature invalid; {@code 3001} no Square connection
 *       for the tenant referenced in the webhook payload; {@code 3002} Square OAuth
 *       token exchange or refresh failed; {@code 3003} POS payment sync failed.</li>
 *   <li>{@code 3100-3199} — <em>Phase A</em>: Idempotency middleware.
 *       {@code 3100} {@code Idempotency-Key} header missing on an
 *       {@link com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute}-annotated
 *       endpoint (400 Bad Request); {@code 3101} concurrent duplicate key race
 *       — MongoDB unique index violation on first-insert attempt (409 Conflict).</li>
 *   <li>{@code 3200-3299} — <em>Phase A</em>: API versioning and auth-mode
 *       <em>startup/config</em>. {@code 3200} reserved for auth-mode configuration
 *       errors (e.g. {@code zitadel} mode with blank {@code jwksUri} — fails fast at
 *       startup). Kept distinct from the A2 runtime range below.</li>
 *   <li>{@code 3300-3399} — <em>Phase A2</em>: Zitadel federation <em>runtime</em>
 *       claim/login failures. {@code 3300} Zitadel token missing the organization
 *       claim (401); {@code 3301} org claim does not map to any tenant (403);
 *       {@code 3302} token carries no role mappable to a KMOSF role (403);
 *       {@code 3303} password login disabled — deployment federates to Zitadel
 *       ({@code POST /auth/login} → 410 Gone, body points at {@code /auth/discovery}).</li>
 *   <li>{@code 3400-3499} — <em>Phase C</em>: Projects / Milestones / Tasks.
 *       {@code 3400} Project not found (404); {@code 3401} Project name blank (400);
 *       {@code 3402} invalid Project status transition (409);
 *       {@code 3410} Milestone not found (404); {@code 3411} Milestone name blank (400);
 *       {@code 3412} invalid Milestone status transition (409); {@code 3413} Milestone
 *       already spawned an invoice — idempotent re-spawn attempted (409, defensive);
 *       {@code 3420} Task not found (404); {@code 3421} Task title blank (400);
 *       {@code 3422} invalid Task status transition (409);
 *       {@code 3431} Deal not found for conversion (404); {@code 3432} Deal not WON —
 *       cannot convert (409); {@code 3433} Project code generation failed after retry
 *       (500, defensive — should never fire).</li>
 *   <li>{@code 3500-3599} — <em>Phase D</em>: Time &amp; Expenses.
 *       <em>TimeEntry:</em> {@code 3500} TimeEntry not found (404); {@code 3501} userId
 *       required (400); {@code 3502} startedAt required (400); {@code 3503} endedAt before
 *       startedAt (400); {@code 3504} cannot edit an INVOICED time entry (409);
 *       {@code 3505} a timer is already running for this user (409); {@code 3506} no timer
 *       running to stop (409); {@code 3507} invalid time-entry billing-state transition (409,
 *       defensive).
 *       <em>Invoice-from-time:</em> {@code 3520} no unbilled time entries to invoice (409);
 *       {@code 3521} billable time entry has no rate and no default supplied (400);
 *       {@code 3522} all selected time entries already invoiced (409).
 *       <em>Expense:</em> {@code 3511} userId required (400); {@code 3512} description
 *       required (400); {@code 3513} amount must be &gt; 0 (400); {@code 3514} incurredOn
 *       required (400); {@code 3515} invalid expense approval transition (409);
 *       {@code 3516} rejection reason required when rejecting (400); {@code 3517} cannot
 *       edit/decide an INVOICED expense (409).
 *       <em>Invoice-from-expenses:</em> {@code 3530} no eligible (approved+billable+unbilled)
 *       expenses (409); {@code 3531} reserved; {@code 3532} all selected expenses already
 *       invoiced (409).</li>
 *   <li>{@code 3600-3699} — <em>Phase E</em>: Billing / Recurring / Stripe.
 *       <em>RecurringInvoice:</em> {@code 3601} templateName blank (400);
 *       {@code 3602} lineItems empty (400); {@code 3603} rrule blank (400);
 *       {@code 3604} seedAt required (400); {@code 3605} RecurringInvoice not found (404);
 *       {@code 3606} invalid RecurringInvoice status transition (409);
 *       {@code 3607} cannot modify an ENDED RecurringInvoice (409).
 *       <em>Stripe webhook:</em> {@code 3610} webhook event missing id (400, defensive —
 *       a duplicate event is acknowledged 200, not an error).
 *       <em>Stripe checkout:</em> {@code 3620} Stripe apiKey not configured for checkout
 *       (412 — distinct from the webhook's {@code 2511} missing-signing-secret);
 *       {@code 3621} Stripe checkout session/payment-link creation failed (502).
 *       <em>Accounting-push:</em> {@code 3630} invoice not eligible for accounting push
 *       (409, defensive).
 *       <em>Invoice numbering (blocker resolution):</em> {@code 3640} invoice number
 *       generation failed after retry (500, defensive — should never fire; mirrors the
 *       Phase-C {@code 3433} project-code-generation guard). Reused (not re-allocated):
 *       {@code 1300} invalid RRULE
 *       (owned by {@code Rfc5545RecurringSchedule}); {@code 2300} Invoice not found;
 *       {@code 2510}/{@code 2511} Stripe webhook connection/secret; {@code 2800-2802}
 *       QBO; {@code 3100}/{@code 3101} idempotency.</li>
 *   <li>{@code 3700-3799} — <em>Phase F</em>: Contracts / Documenso.
 *       <em>ContractTemplate:</em> {@code 3701} name blank (400); {@code 3702}
 *       bodyTemplate blank (400); {@code 3705} template not found or inactive (404).
 *       <em>Contract:</em> {@code 3703} title blank (400); {@code 3704} quote not
 *       ACCEPTED — cannot spawn SOW (409); {@code 3706} voidReason required when
 *       voiding (400); {@code 3707} Contract not found (404); {@code 3708} contract
 *       number generation failed after retry (500, defensive — mirrors {@code 3433}/
 *       {@code 3640}); {@code 3709} invalid Contract status transition (409).
 *       <em>Documenso webhook:</em> {@code 3710} webhook HMAC signature invalid (401)
 *       — the stable AC-F3 errorCode; {@code 3711} invalid tenant id in webhook
 *       path (400); {@code 3712} Documenso not connected for tenant (404); {@code
 *       3713} webhookSigningSecret not configured (412); {@code 3714} webhook body
 *       not JSON (400); {@code 3715} webhook event missing id (400, defensive);
 *       {@code 3716} no Contract for the Documenso document id (404, defensive);
 *       {@code 3717} signed-PDF fetch/store failed (502).
 *       <em>Documenso send:</em> {@code 3720} apiToken not configured (412);
 *       {@code 3721} Documenso send failed (502). Reused (not re-allocated):
 *       {@code 1310}/{@code 1311} file storage; {@code 3100}/{@code 3101}
 *       idempotency; deal/project codes thrown by the reused
 *       {@code moveStage}/{@code convertFromDeal} services
 *       ({@code 1400}/{@code 1401}/{@code 3431}/{@code 3432}/{@code 3433}).</li>
 *   <li>{@code 3800-3899} — <em>Phase G</em>: Portal expansion.
 *       <em>Portal ownership / scoping (new ownership gates — the deliberate same-404
 *       confidentiality choice: not-found and not-owned return the SAME errorCode so a
 *       portal user cannot distinguish "entity exists but isn't yours" from "no such
 *       entity" — no enumeration oracle):</em>
 *       {@code 3801} invoice not found or not owned by the caller's contact/company (404);
 *       {@code 3802} quote not found or not owned (404);
 *       {@code 3803} project not found or not owned (404);
 *       {@code 3804} contract not found or not owned (404);
 *       {@code 3805} portal quote status transition not permitted — only
 *       {@code SENT→ACCEPTED} or {@code SENT→DECLINED} from the portal (409);
 *       {@code 3806} contract signed PDF not available — contract is not in SIGNED
 *       status or {@code signedPdfStorageRef} is absent (409);
 *       {@code 3807} project file not found or not accessible under the portal access
 *       policy (404, defensive — the presign-download {@code 1311} foreign-tenant
 *       rejection is the storage-layer backstop). Reserved for future portal growth:
 *       {@code 3808-3899}.
 *       Reused (NOT re-allocated): {@code 1250}/{@code 1251}/{@code 1252}
 *       (existing {@code PortalLinkedContactResolver} codes — surfaced unchanged);
 *       {@code 2200} Quote not found; {@code 2300} Invoice not found;
 *       {@code 2510}/{@code 3620}/{@code 3621} Stripe not connected / apiKey missing /
 *       checkout failed (thrown by the reused {@code StripeCheckoutService});
 *       {@code 1310}/{@code 1311} file storage (reused {@code FileStorageService});
 *       {@code 3100}/{@code 3101} idempotency middleware.</li>
 *   <li>{@code 3900-3999} — <em>Phase H</em>: External-integrations glue.
 *       <em>Cal.com webhook:</em> {@code 3900} webhook HMAC signature invalid (401);
 *       {@code 3901} Cal.com not connected for the tenant (404 — the cross-integration
 *       not-connected convention; {@code 2510} is the documented cross-integration
 *       fallback code reused on the send/client path); {@code 3902} webhook body not
 *       JSON or missing event id (400); {@code 3903} Cal.com booking references no
 *       resolvable contact (advisory-skip, logged — not a hard error).
 *       <em>IMAP inbound poller:</em> {@code 3910} IMAP poll connection/auth failure
 *       (502, logged, poller-internal — never surfaced to an HTTP client); {@code 3911}
 *       IMAP message unparseable (skipped, logged).
 *       <em>Activepieces seed:</em> {@code 3920} Activepieces seed target URL invalid
 *       (400); {@code 3921} Activepieces seed not permitted — module disabled (403/404).
 *       <em>Portal Zitadel federation (opt-in per {@code Tenant.zitadelOrgId}):</em>
 *       {@code 3930} portal Zitadel federation not enabled for this tenant —
 *       {@code zitadelOrgId == null} (404, same-as-not-found confidentiality posture);
 *       {@code 3931} portal Zitadel callback state/nonce invalid (401).
 *       Reserved for future glue growth: {@code 3940-3999}.
 *       Reused (NOT re-allocated): {@code 2510} (provider-not-connected cross-integration
 *       convention, used by Stripe/Documenso send paths — the Cal.com webhook uses the
 *       Phase-H-local {@code 3901}; {@code 2510} is the documented cross-integration
 *       fallback); {@code 3100}/{@code 3101} (idempotency middleware);
 *       {@code 3300-3303} (A2 Zitadel runtime claim/login failures — surfaced unchanged
 *       on the portal-federation path).</li>
 *   <li>{@code 4000-4099} — <em>Phase 1 (NMM AI intake)</em>: voicemail-to-lead pipeline.
 *       <em>Twilio voice/voicemail webhook:</em> {@code 4000} Twilio request signature
 *       (X-Twilio-Signature) invalid (401 — the stable AC errorCode; mirrors the Cal.com
 *       {@code 3900} sig-invalid posture); {@code 4001} Twilio not connected for the tenant
 *       (404 — the Phase-1-local not-connected code; {@code 2510} remains the documented
 *       cross-integration fallback); {@code 4002} webhook params malformed or missing the
 *       {@code CallSid} dedupe key (400, defensive — a duplicate CallSid is acknowledged
 *       200, not an error); {@code 4003} invalid tenant id in the webhook path (400).
 *       Reserved for future intake-channel growth: {@code 4004-4099}.
 *       Reused (NOT re-allocated): {@code 1200-1203} (AI budget gate + Anthropic call
 *       failure + missing-key — surfaced unchanged by the new {@code VoicemailExtractionService},
 *       which mirrors {@code AnthropicAiAssistService}); {@code 2510} (provider-not-connected
 *       cross-integration convention); {@code 2530-2532} (Twilio SMS recipient/send/secret —
 *       surfaced unchanged by the reused {@code TwilioSmsService} on the notify + auto-ack
 *       paths); {@code 1300} Activity-not-found (the reused {@code ActivityCrudService}).</li>
 *   <li>{@code 4010-4039} — <em>Phase 2 (NMM AI intake)</em>: "is this a mole?" photo-triage
 *       pipeline (Feature B). <em>Public photo-intake widget:</em> {@code 4010} widget token type
 *       mismatch (the token's {@code widgetType} claim is not {@code "mole-triage"}, 401 — mirrors
 *       the home-services {@code 2700} widget-type-mismatch posture; the generic token-rejection
 *       codes {@code 1600-1603} from {@code PublicWidgetTokenService} are surfaced unchanged for
 *       missing/malformed/bad-signature/expired tokens); {@code 4011} no image part in the
 *       multipart submission (400); {@code 4012} unsupported image media type — only
 *       {@code image/jpeg|png|webp|gif} are accepted (415). Carved out of the reserved
 *       photo-triage-growth range: {@code 4013-4029} are now <em>Phase 3</em> (the tripwire IS
 *       photo-triage growth — see below); {@code 4030-4039} remain reserved. Reused (NOT
 *       re-allocated): {@code 1200-1203} (AI budget gate +
 *       Anthropic call failure + missing-key — surfaced unchanged by the new
 *       {@code MoleVisionService}, which mirrors {@code AnthropicAiAssistService}); {@code 1310}/
 *       {@code 1311} (file storage — surfaced unchanged by the reused {@code FileStorageService}
 *       on the {@code putBytes} store path); {@code 2530-2532} (Twilio SMS recipient/send/secret —
 *       surfaced unchanged by the reused {@code TwilioSmsService} on the notify path);
 *       {@code 1300} Activity-not-found (the reused {@code ActivityCrudService}).</li>
 *   <li>{@code 4013-4029} — <em>Phase 3 (NMM AI intake)</em>: coverage-window automation — the B2
 *       re-activity tripwire + the coverage-window check-in nudge. A sub-block carved from
 *       Phase-2's reserved {@code 4013-4039} photo-triage-growth range (the tripwire IS
 *       photo-triage growth). <em>Mole-tripwire public report endpoint:</em> {@code 4013} tripwire
 *       token type mismatch (the token's {@code widgetType} claim is not {@code "mole-tripwire"},
 *       401 — mirrors the Phase-2 {@code 4010} / home-services {@code 2700} widget-type-mismatch
 *       posture); {@code 4014} no image part in the multipart tripwire submission (400);
 *       {@code 4015} unsupported image media type — only {@code image/jpeg|png|webp|gif} are
 *       accepted (415); {@code 4016} the tripwire token's Project no longer exists for the tenant
 *       (404, defensive — a deleted Project after a token was issued). Reserved for future
 *       coverage-window growth: {@code 4017-4029}. Reused (NOT re-allocated): {@code 1600-1603}
 *       (generic tripwire-token rejections — missing/malformed/bad-signature/expired — surfaced
 *       unchanged by the new {@code MoleTripwireTokenService}, which mirrors
 *       {@code PublicWidgetTokenService}'s HMAC scheme); {@code 1200-1203} (AI budget gate +
 *       Anthropic call failure + missing-key — surfaced unchanged by the reused
 *       {@code MoleVisionService}); {@code 1310}/{@code 1311} (file storage — reused
 *       {@code FileStorageService} {@code putBytes}); {@code 2530-2532} (Twilio SMS — reused
 *       {@code TwilioSmsService} on the notify + nudge paths); {@code 1300} Activity-not-found
 *       (reused {@code ActivityCrudService}); {@code 3410}/{@code 3411} Milestone not-found /
 *       name-blank (the UNCHANGED {@code MilestoneService.create} surfaces these on the
 *       re-treatment-Milestone path); {@code 1800} RoleGuard ADMIN-required (the admin
 *       tripwire-token issuance endpoint).</li>
 *   <li>{@code 4100-4199} — <em>Phase J</em>: Contractor / time-management vertical.
 *       <em>Project assignment:</em> {@code 4101} Project not found for assignment (404);
 *       {@code 4102} userId required (400) / User not found (404); {@code 4106} assignment
 *       not found (404).
 *       <em>Team directory:</em> {@code 4102} email required (400, shared user-identity
 *       code); {@code 4103} displayName required (400); {@code 4104} a team member with
 *       this email already exists (409); {@code 4105} unknown user status (400);
 *       {@code 4140} team member not found (404).
 *       <em>Reserved for later sub-phases:</em> {@code 4120} time exists but none approved
 *       (timesheet billing gate); {@code 4130-4135} contractor scoping (self-resolver,
 *       not-assigned / not-owned, cross-user write, denyRole); {@code 4150-4151} timesheet
 *       submit/approve transitions + reject-reason. Reused (NOT re-allocated):
 *       {@code 1800} RoleGuard ADMIN-required (team + assignment endpoints);
 *       {@code 3100}/{@code 3101} idempotency middleware (assignment POST).</li>
 * </ul>
 */
@Slf4j
@Component
@Order(-2)
@RequiredArgsConstructor
public class GlobalErrorHandler implements ErrorWebExceptionHandler {

    private final ObjectMapper objectMapper;

    @Override
    @NonNull
    public Mono<Void> handle(@NonNull ServerWebExchange exchange, @NonNull Throwable ex) {
        ProblemDetail problem = translate(ex);
        problem.setProperty("correlationId", UUID.randomUUID().toString());
        if (problem.getStatus() >= 500) {
            log.error("Unhandled exception (correlationId={})", problem.getProperties().get("correlationId"), ex);
        } else {
            log.debug("Translated exception {} -> {}", ex.getClass().getSimpleName(), problem.getStatus());
        }
        exchange.getResponse().setStatusCode(HttpStatus.valueOf(problem.getStatus()));
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        return write(exchange, problem);
    }

    private ProblemDetail translate(Throwable ex) {
        if (ex instanceof DigiPresBeException dpb) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(
                    HttpStatus.valueOf(dpb.getHttpStatusCode()),
                    dpb.getMessage());
            pd.setType(URI.create("https://kmosf/errors/" + dpb.getErrorCode()));
            pd.setProperty("errorCode", dpb.getErrorCode());
            return pd;
        }
        if (ex instanceof WebExchangeBindException bind) {
            ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST,
                    "Request validation failed");
            pd.setProperty("fieldErrors", bind.getFieldErrors().stream()
                    .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                    .toList());
            return pd;
        }
        if (ex instanceof ResponseStatusException rse) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.valueOf(rse.getStatusCode().value()),
                    rse.getReason() != null ? rse.getReason() : rse.getMessage());
        }
        if (ex instanceof AccessDeniedException) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, "Access denied");
        }
        if (ex instanceof AuthenticationException) {
            return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");
    }

    private Mono<Void> write(ServerWebExchange exchange, ProblemDetail problem) {
        return Mono.fromCallable(() -> objectMapper.writeValueAsBytes(problem))
                .flatMap(bytes -> {
                    DataBuffer buf = exchange.getResponse().bufferFactory().wrap(bytes);
                    return exchange.getResponse().writeWith(Mono.just(buf));
                });
    }
}
