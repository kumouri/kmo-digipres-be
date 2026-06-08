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
 *   <li>{@code 4030-4049} — <em>NMM GBP review-reply automation</em>: Google Business Profile
 *       review-reply pipeline (claims the {@code 4030-4039} block Phase 2 reserved, extended to
 *       {@code 4049}). <em>GBP API client:</em> {@code 4030} Google Business Profile not connected
 *       for the tenant — no {@code IntegrationConnection(provider="google-business")} (404, the
 *       Phase-local not-connected code; {@code 2510} remains the documented cross-integration
 *       fallback); {@code 4031} GBP API fetch-reviews / post-reply call failed (502 — a non-2xx or
 *       downstream throwable, after the Resilience4j retry/breaker). <em>OAuth2 access-token
 *       refresh:</em> {@code 4034} GBP token refresh failed (502) — raised by {@code GbpTokenService}
 *       when, after a GBP API call returned 401, the stored {@code refreshToken} is missing/blank,
 *       the configurable {@code kmosf.gbp.token-url} endpoint returns a non-2xx / non-JSON body, or
 *       the response carries no {@code access_token} (the access token cannot be rotated; a
 *       re-consent is required). <em>Admin approve/post surface:</em> {@code 4032} review-reply
 *       draft not found for the tenant (404); {@code 4033} review-reply is not in {@code DRAFTED}
 *       status — cannot post/skip an already POSTED/SKIPPED draft (409, defensive). Reserved for
 *       future review-automation growth: {@code 4035-4049}. Reused (NOT re-allocated): {@code 1200-1203} (AI budget gate +
 *       Anthropic call failure + missing-key — surfaced unchanged by the new
 *       {@code GbpReplyDraftService}, which mirrors {@code AnthropicAiAssistService}); {@code 2530-2532}
 *       (Twilio SMS recipient/send/secret — surfaced unchanged by the reused {@code TwilioSmsService}
 *       on the notify path); {@code 1300} Activity-not-found (reused {@code ActivityCrudService}, if
 *       ever surfaced); {@code 1800} RoleGuard ADMIN-required (the admin list/post/skip endpoints).</li>
 *   <li>{@code 4100-4199} — <em>Phase J</em>: Contractor / time-management vertical.
 *       <em>Project assignment:</em> {@code 4101} Project not found for assignment (404);
 *       {@code 4102} userId required (400) / User not found (404); {@code 4106} assignment
 *       not found (404).
 *       <em>Team directory:</em> {@code 4102} email required (400, shared user-identity
 *       code); {@code 4103} displayName required (400); {@code 4104} a team member with
 *       this email already exists (409); {@code 4105} unknown user status (400);
 *       {@code 4140} team member not found (404).
 *       <em>Contractor scoping (J2):</em> {@code 4130} caller is not a contractor (403,
 *       {@code ContractorSelfResolver}); {@code 4131} token carries no user id (403);
 *       {@code 4132} project not found / not actively assigned (404, same-404 no-enumeration
 *       oracle, {@code ContractorAccessGuard#requireAssignedProject}); {@code 4133} time
 *       entry / expense not found / not owned (404, same-404); {@code 4134} cross-user write
 *       — a contractor body {@code userId != self} (400); {@code 4135} role not permitted —
 *       a CONTRACTOR token on a broad staff reader ({@code RoleGuard.denyRole}, 403).
 *       <em>Timesheet lifecycle + invoicing gate (J3):</em> {@code 4120} time entries exist
 *       but none are approved — the owning timesheet must be approved first (409,
 *       {@code TimeEntryService.createInvoiceFromTime} approved-only gate); {@code 4150}
 *       illegal timesheet lifecycle transition (409, {@code TimesheetService} —
 *       submit/approve/reject/reopen {@code ILLEGAL_TRANSITIONS}); {@code 4151} timesheet
 *       reject reason required (400); {@code 4152} timesheet not found for the tenant (404,
 *       the admin lifecycle load-by-id path — the contractor self-surface uses the same-404
 *       {@code 4133} via {@code ContractorAccessGuard#requireOwnedTimesheet}).
 *       <em>Payout + margin report (J4):</em> {@code 4160} payout report request invalid —
 *       missing {@code userId}, missing {@code from}/{@code to}, or {@code to} not after
 *       {@code from} (400, {@code PayoutController}; a pure read otherwise returns data, so
 *       {@code 4160} is the only J4-allocated code). Reused (NOT
 *       re-allocated):
 *       {@code 1800} RoleGuard ADMIN-required (team + assignment + payout-report endpoints);
 *       {@code 3100}/{@code 3101} idempotency middleware (assignment POST);
 *       {@code 3500}/{@code 3502}/{@code 3503}/{@code 3505}/{@code 3506} and
 *       {@code 3511}-{@code 3517} surfaced unchanged by the reused {@code TimeEntryService} /
 *       {@code ExpenseService} on the contractor self-scoped time/expense paths.</li>
 *   <li>{@code 4200-4219} — <em>HS-1 (Home Services — "Front Desk That Never Sleeps")</em>:
 *       multi-trade voicemail → DRAFT WorkOrder. The free band above Phase J (which ends at
 *       {@code 4160}; {@code 4100-4199} is Phase J's reserved block). {@code 4200} multi-trade
 *       extraction strategy misconfigured — an explicitly-set {@code voicemailVertical} value that
 *       is not a registered strategy (defensive 500; in practice the
 *       {@code VoicemailExtractionStrategyResolver} defaults to mole-pest and logs, so this is
 *       reserved for an explicitly-bad config rather than thrown today); {@code 4201}
 *       home-services voicemail produced a DRAFT WorkOrder but {@code field-service} /
 *       {@code WorkOrderService} is unavailable on this server — logged advisory, <strong>not</strong>
 *       surfaced to Twilio (the pipeline degrades to Contact+Activity+notify; the code is reserved
 *       for the admin/health surface); {@code 4202} Missed-Call Inbox read requested but
 *       home-services is not enabled for the tenant (404 — surfaced via the shared
 *       {@code TenantModuleRegistry.requireEnabled} {@code 1130}/{@code 1132} module-gate codes;
 *       mirrors the {@code 2700}/{@code 3930} not-enabled posture). Reserved for HS growth:
 *       {@code 4203-4209} (HS-3 {@code 4215-4219} forward/booking-link). Reused (NOT re-allocated):
 *       {@code 1200-1203} (AI budget gate +
 *       Anthropic call failure + missing-key — surfaced unchanged by the shared
 *       {@code VoicemailExtractionService} transport the per-vertical strategies call);
 *       {@code 4000-4003} (Twilio sig/not-connected/CallSid/tenant-id — the voicemail webhook still
 *       owns these); {@code 1330}/{@code 1331}/{@code 1332} ({@code WorkOrderService} WO-not-found /
 *       terminal / number-generation — the reused {@code WorkOrderService.create} path);
 *       {@code 2530-2532} (Twilio SMS — reused {@code TwilioSmsService} on the notify + auto-ack
 *       paths); {@code 1300} Activity-not-found (reused {@code ActivityCrudService}).</li>
 *   <li>{@code 4210-4214} — <em>HS-2 (Home Services — "Front Desk That Never Sleeps")</em>:
 *       equipment-nameplate photo enrichment. The caller's photo arrives over a tokenized HTTPS
 *       upload ({@code EquipmentPhotoController}, the {@code MoleTriageController} /
 *       {@code MoleTripwireController} pattern) — NOT an inbound Twilio MMS webhook, so HS-2 adds no
 *       new 10DLC surface (plan §3 design fork). {@code 4210} equipment-photo token type mismatch —
 *       a correctly-signed token whose {@code widgetType} is not {@code "equipment-photo"} (401,
 *       the {@code 4013}/{@code 4010} widget-type-mismatch posture); {@code 4211} unsupported image
 *       media type (415; only {@code image/jpeg|png|webp|gif}, the shared
 *       {@code AiVisionService.isSupportedMediaType} gate); {@code 4212} the token references a
 *       WorkOrder that no longer exists for the tenant (404, public-upload path — same-404
 *       no-enumeration); {@code 4213} equipment-photo upload missing its {@code image} part (400, the
 *       {@code 4011}/{@code 4014} missing-image posture); {@code 4214} WorkOrder not found for
 *       equipment-photo-token issuance (404, the ADMIN {@code EquipmentPhotoTokenController}
 *       {@code {workOrderId}} load — tenant-scoped, the {@code 4016} tripwire-token-issuance
 *       precedent). Reused (NOT re-allocated): {@code 1200-1203} (AI budget gate + Anthropic vision
 *       call failure + missing-key — the shared {@code AiVisionService.extract} the nameplate read
 *       calls, best-effort so they never surface to the caller); {@code 1600-1603} (generic
 *       equipment-photo-token rejections — missing/malformed/bad-signature/expired, the
 *       {@code EquipmentPhotoTokenService} reuse of the widget/tripwire token vocabulary);
 *       {@code 1310} storage-not-configured (reused {@code FileStorageService.putBytes});
 *       {@code 1300} Activity-not-found (reused {@code ActivityCrudService}); {@code 1800}
 *       not-ADMIN (reused {@code RoleGuard} on the token issuer); {@code 2530-2532} (Twilio SMS —
 *       reused {@code TwilioSmsService} on the owner-digest notify).</li>
 *   <li>{@code 4215-4219} — <em>HS-3 (Home Services — "Front Desk That Never Sleeps")</em>:
 *       EMERGENCY live-forward + two-sided outbound SMS. The live-forward is a <strong>deterministic
 *       IVR gate on the voice webhook</strong> (a {@code <Gather>} "press 1 for the on-call tech")
 *       whose digit is handled by a new signature-verified callback
 *       {@code POST /public/integrations/twilio/{id}/voice/gather} — AI urgency is only known
 *       <em>after</em> transcription, so the forward cannot be AI-gated mid-call (plan §6 timing).
 *       The gate is the per-tenant {@code IntegrationConnection(twilio).config.onCallPhone}: absent
 *       → the voice webhook returns the EXISTING greeting+record TwiML byte-unchanged (the NMM gate).
 *       The caller booking-link SMS is gated on {@code config.bookingLinkUrl} and the owner digest is
 *       EMERGENCY-flagged only when the AI triage urgency is {@code EMERGENCY} — both gated so the
 *       mole/default auto-ack + owner-notify bodies stay byte-identical. No new error condition is
 *       <em>thrown</em> today — the band is RESERVED ({@code 4215-4219}) for future HS-3 forward /
 *       booking-link conditions; the gather callback reuses the voice webhook's existing
 *       {@code 4000-4003} (signature / not-connected / CallSid / tenant-id) and the
 *       {@code 2530-2532} Twilio-SMS codes (the reused {@code TwilioSmsService} on the booking-link
 *       + owner-digest sends). <strong>Go-live needs a real Twilio number, a verified on-call number,
 *       and an A2P 10DLC campaign for the caller-facing booking SMS</strong> — separate human/external
 *       steps, out of the implementation loop (plan §6).</li>
 *   <li>{@code 4220-4224} — <em>ChairFill CF-1 (Personal-care / salon flagship)</em>: nightly
 *       no-show risk model. The free band above HS (which ends at {@code 4219}). The whole module is
 *       gated on {@code kmosf.modules.chairfill.enabled} (default off) + {@code Tenant.enabledModules}
 *       membership, so most paths reuse existing codes and CF-1 mints little. {@code 4220} ChairFill
 *       not enabled for the tenant (surfaced via the shared {@code TenantModuleRegistry.requireEnabled}
 *       {@code 1130}/{@code 1132} module-gate codes on every {@code NoShowRiskController} handler —
 *       mirrors the {@code 4202}/{@code 2700}/{@code 3930} not-enabled posture); {@code 4221} a
 *       no-show retrain job is already running for this tenant ({@code POST /chairfill/risk/retrain}
 *       → 409, the lead-scoring {@code 3001} analogue, kept ChairFill-local for clarity);
 *       {@code 4222-4224} reserved for CF-1 growth. Reused (NOT re-allocated): {@code 1130}/{@code 1132}
 *       (module-gate, as above); {@code 2900} salon Booking-not-found (the reused {@code BookingRepository}
 *       reads). The scoring itself is pure Smile ML — <strong>zero per-booking token cost, no AI-budget
 *       (1200-1203) path</strong>. The downstream {@code BOOKING_RISK_SCORED} event is advisory (it does
 *       not drive any core mutation; CF-2 will subscribe). The {@code NoShowRiskController} is
 *       {@code @ConditionalOnProperty}-gated, so it is absent from the generated OpenAPI spec when the
 *       module is off (the HS precedent).</li>
 *   <li>{@code 4225-4229} — <em>ChairFill CF-2 (Personal-care / salon flagship)</em>: risk-tiered
 *       prevention. The {@code RiskTieredPreventionService} subscriber on {@code BOOKING_RISK_SCORED}
 *       (HIGH ⇒ require a deposit via the reused salon deposit path + an extra confirmation SMS;
 *       LOW/MEDIUM ⇒ a single Claude-personalized reminder SMS) + the owner-tunable
 *       {@code ChairFillReminderAutomation} baseline WorkflowRule seeder. <strong>CF-2 mints NO new
 *       error codes</strong> — it is a fully best-effort, additive subscriber (a Claude / Twilio /
 *       deposit failure logs + degrades, never surfaces an HTTP error). The band {@code 4225-4229}
 *       is RESERVED for future CF-2 growth. Reused (NOT re-allocated): the AI band {@code 1200-1203}
 *       (budget gate / Anthropic non-200 / missing-key, via the {@code ReminderCopyService} sibling
 *       of {@code GbpReplyDraftService}); the Twilio SMS codes {@code 2530-2532} (and the
 *       {@code IntegrationConnectionService} {@code 2501} missing-connection — all swallowed
 *       best-effort by the subscriber); the salon deposit/booking {@code 2900} (the reused
 *       {@code SalonBookingService.requireDepositNow} path mints a DRAFT {@code Invoice} exactly like
 *       create-time). TCPA-safe: a {@value com.kumouri.kmodigipresbe.module.chairfill.automation.RiskTieredPreventionService#SMS_OPT_OUT_TAG}
 *       contact tag (honors STOP) + a per-contact rolling frequency cap gate every send; the
 *       {@code ReminderLog} ledger (unique per (tenant, booking)) makes a re-fired event a no-op.</li>
 *   <li>{@code 4230-4239} — <em>ChairFill CF-3 (Personal-care / salon flagship)</em>: gap-fill waitlist
 *       auto-offer (the double-YES-correct showpiece). The {@code GapFillService} subscriber on the
 *       additively-emitted {@code BOOKING_CANCELLED} ranks the {@code salon-waitlist} pool (the CF-1
 *       model inverted), Claude drafts a time-boxed offer, and the first inbound YES atomically claims
 *       the freed slot via a slot-level {@code findAndModify} ({@code claimedByContactId:null} guard,
 *       the {@code WorkOrderNumberGenerator} precedent) → winner gets a real booking via the unchanged
 *       {@code SalonBookingService.create} (policy-validated, {@code @Version} backstop), loser(s) get
 *       an apologetic auto-reply. {@code 4230} {@code salon-waitlist} widget-type mismatch — a correctly
 *       -signed widget token whose {@code widgetType} claim is not {@code "salon-waitlist"} (401, the
 *       {@code 2911}/{@code 4010}/{@code 4013} widget-type-mismatch posture; the generic token-rejection
 *       codes {@code 1600-1603} from {@code PublicWidgetTokenService} are surfaced unchanged for
 *       missing/malformed/bad-signature/expired tokens). {@code 4231-4239} reserved for CF-3 growth.
 *       <strong>The inbound-SMS webhook is NET-NEW</strong> ({@code TwilioInboundSmsController} /
 *       {@code InboundSmsService}, {@code POST /public/integrations/twilio/{id}/sms}) — it REUSES the
 *       voicemail webhook's {@code 4000} (signature invalid, 401), {@code 4001} (Twilio not connected,
 *       404), {@code 4003} (invalid tenant id in path, 400) verbatim (NOT re-allocated). Reused (NOT
 *       re-allocated): {@code 1200-1203} (AI budget gate / Anthropic non-200 / missing-key, via the
 *       {@code OfferCopyService} sibling of {@code ReminderCopyService} — all best-effort, degrade to a
 *       generic offer); the Twilio SMS codes {@code 2530-2532} (offer / confirmation / apology sends —
 *       best-effort); the salon booking/deposit {@code 2900}/{@code 2901} (the reused
 *       {@code SalonBookingService.create} → {@code BookingPolicyService.validate} guards the winner's
 *       booking, so even a logic slip cannot double-book); {@code 4000-4003} (the reused inbound Twilio
 *       webhook signature/not-connected/tenant-id codes). TCPA: the waitlist join is the opt-in
 *       (default-safe — only {@code smsOptIn} entries are offered), a STOP inbound sets the CF-2
 *       {@code sms-opt-out} tag, and the atomic claim makes a re-delivered YES a loser (apology), never a
 *       second booking. The {@code WaitlistWidgetController} + {@code TwilioInboundSmsController} are
 *       {@code @ConditionalOnProperty}-gated, so they are absent from the generated OpenAPI spec when the
 *       module is off (the HS / {@code NoShowRiskController} precedent).</li>
 *   <li>{@code 4240-4244} — <em>ChairFill CF-4 (Personal-care / salon flagship)</em>: AI review-reply,
 *       salon-generalized + RAG voice + the reused approval queue (D4). The
 *       {@code SalonReviewReplyService} drafts an on-brand, salon-voiced reply (a brand-tone system
 *       prompt + RAG-retrieved exemplar past <em>approved</em> replies) by calling the unchanged
 *       {@code GbpReplyDraftService}'s additive overload, and parks it DRAFTED in the
 *       <strong>same {@code GbpReviewReply} approval queue NMM uses</strong> — the reused
 *       {@code GbpReviewReplyAdminController} approves (posts, when {@code google-business} is wired,
 *       or copy-ready) / skips it. <strong>Never auto-posted.</strong> The
 *       {@code SalonReviewReplyController} paste-in endpoint ({@code POST /chairfill/reviews/draft},
 *       STAFF-gated + module-gated) is the demo path (no live Google OAuth). <strong>CF-4 mints ONE
 *       new code:</strong> {@code 4240} a paste-in submission with a blank review text (400 — nothing
 *       to draft a reply to). {@code 4241-4244} reserved for CF-4 growth. <strong>Best-effort</strong>:
 *       an exemplar-retrieval failure → no exemplars; a Claude failure ({@code 1200}/{@code 1202}/
 *       {@code 1203}) → a generic on-brand fallback draft — never a thrown error, never a blank, never
 *       a dropped review. Reused (NOT re-allocated): {@code 1200-1203} (AI budget gate / Anthropic
 *       non-200 / missing-key, via the unchanged {@code GbpReplyDraftService}); {@code 4032}/{@code 4033}
 *       (the reused admin approve/skip not-found / not-DRAFTED); {@code 4030}/{@code 4031} (the reused
 *       {@code GbpApiClient.postReply} on approve→post for a GBP-wired salon); {@code 1130}/{@code 1132}
 *       (the shared {@code TenantModuleRegistry.requireEnabled} module gate); {@code 1800} (the
 *       STAFF {@code RoleGuard}). <strong>NMM byte-equivalent:</strong> the GBP poller/ledger/admin
 *       queue/drafting are unchanged; the salon prompt + RAG exemplars are additive + per-tenant +
 *       module-gated. The {@code SalonReviewReplyController} is {@code @ConditionalOnProperty}-gated,
 *       so it is absent from the generated OpenAPI spec when the module is off.</li>
 *   <li>{@code 4245-4249} — <em>ChairFill CF-5a (Personal-care / salon flagship)</em>: the
 *       staff-facing waitlist-board read ({@code WaitlistBoardController} —
 *       {@code GET /chairfill/waitlist/board|entries|offers}) backing the CF-5 board FE. A pure read
 *       over the CF-3 {@code WaitlistEntry}/{@code WaitlistOffer} collections: the OPEN entries +
 *       recent offers (with status), newest first. <strong>CF-5a mints NO new error code</strong> —
 *       it reuses the shared {@code TenantModuleRegistry.requireEnabled} module gate ({@code 1130}/
 *       {@code 1132}, the {@code 4220}/{@code 4202}/{@code 2700}/{@code 3930} not-enabled posture) and
 *       the {@code RoleGuard} STAFF gate ({@code 1800}). The band {@code 4245-4249} is RESERVED for
 *       future board-read growth. The {@code WaitlistBoardController} is
 *       {@code @ConditionalOnProperty}-gated, so it is absent from the generated OpenAPI spec when the
 *       module is off (the {@code NoShowRiskController}/{@code SalonReviewReplyController} precedent).</li>
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
