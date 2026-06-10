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
 *   <li>{@code 4250-4259} — <em>Real Estate Concierge RE-1 (flagship #3)</em>: the {@code realestate}
 *       module + {@code Listing}/{@code ListingDisclosure} + disclosure-text indexing + the strict-grounded,
 *       cited listing concierge over inbound SMS. The whole module is gated on
 *       {@code kmosf.modules.realestate.enabled} (default off) + {@code Tenant.enabledModules} membership.
 *       {@code 4250} realestate not enabled for the tenant (surfaced via the shared
 *       {@code TenantModuleRegistry.requireEnabled} {@code 1130}/{@code 1132} module-gate codes on every
 *       {@code ListingController}/{@code ListingDisclosureController} handler — the
 *       {@code 4220}/{@code 4202}/{@code 2700}/{@code 3930} not-enabled posture); {@code 4251} inbound SMS
 *       could not resolve a listing (no tracked-number match + no in-window recency fallback) — logged, the
 *       buyer is asked to clarify, 200 returned to Twilio (advisory, never thrown as HTTP); {@code 4252}
 *       disclosure-text indexing degraded — embedding unavailable / failed (advisory, {@code indexedAt}
 *       left null, the disclosure is never lost; a re-index retries — never thrown); {@code 4253}
 *       listing/disclosure not found (or not owned) on agent CRUD, and missing disclosure text on create
 *       (404/400). {@code 4254-4259} reserved for RE growth. <strong>The inbound-SMS webhook is REUSED</strong>
 *       ({@code TwilioInboundSmsController}/{@code InboundSmsService}, {@code POST
 *       /public/integrations/twilio/{id}/sms}) — the {@code smsMode="realestate"} seam delegates non-STOP
 *       bodies to the {@code ConciergeInboundRouter}; STOP still wins first (TCPA). It REUSES the voicemail
 *       webhook's {@code 4000} (signature invalid, 401), {@code 4001} (not connected, 404), {@code 4003}
 *       (invalid tenant id in path, 400) verbatim (NOT re-allocated). Reused (NOT re-allocated):
 *       {@code 1200-1203} (AI budget gate / Anthropic non-200 / missing-key, via the
 *       {@code ConciergeAnswerService} sibling of {@code OfferCopyService} — all best-effort, a failure
 *       becomes a graceful {@code HANDOFF}, never a fabricated or empty answer); {@code 2530-2532} (Twilio
 *       SMS recipient/send/secret — the reused {@code TwilioSmsService} on the answer/handoff/clarify
 *       sends, best-effort); {@code 1800} ({@code RoleGuard} STAFF on the agent CRUD controllers). The two
 *       controllers are {@code @ConditionalOnProperty}-gated, so they are absent from the generated OpenAPI
 *       spec when the module is off. <strong>No live Twilio / Anthropic / OpenAI / Atlas in tests</strong>
 *       (WireMock + a test {@code VectorIndex}). <strong>ChairFill byte-equivalent when {@code smsMode}
 *       unset:</strong> the seam is inert (no router consulted), so the CF-3 YES/STOP path is unchanged.</li>
 *   <li>{@code 4260-4262} — <em>Real Estate Concierge RE-2 (flagship #3)</em>: multi-turn qualification +
 *       the buyer {@code Deal} + the unchanged nightly {@code LeadScoringV2Service} tiering + the
 *       hot-handoff. All gated on {@code kmosf.modules.realestate.enabled}; RE-2 adds NO new ML and does
 *       NOT modify the scorer — the materialized concierge {@code Deal} flows through the shipped scorer,
 *       which emits the existing {@code LEAD_SCORE_UPDATED} that the {@code LeadHandoffService} subscriber
 *       acts on. <strong>RE-2 mints only advisory codes</strong> (every path is best-effort — a Claude/SMS
 *       failure never drops the conversation or corrupts the Deal): {@code 4260} qualification extraction
 *       degraded/blank (the {@code QualificationExtractionService} Claude/parse failure — logged, the
 *       conversation continues with the accumulated partial qualification, never thrown); {@code 4261} deal
 *       materialization conflict (the {@code QualificationService} Contact/Deal upsert failure — logged,
 *       the conversation keeps the qualification, never thrown); {@code 4262} reserved for RE-2 growth.
 *       Reused (NOT re-allocated): {@code 1200-1203} (AI budget gate / Anthropic non-200 / missing-key, via
 *       the {@code QualificationExtractionService} — best-effort, a failure degrades to an empty
 *       qualification); {@code 2530-2532} (Twilio SMS recipient/send/secret — the reused
 *       {@code TwilioSmsService} on the best-effort hot-handoff notify); the {@code Deal} CRUD codes
 *       ({@code 1400/1401}). The hot-handoff is idempotent per {@code (tenant, deal)} via a
 *       {@code HotHandoffLog} ledger-insert-FIRST (the {@code ReminderLog}/{@code TwilioVoicemailEvent}
 *       precedent) and re-checks {@code Tenant.enabledModules} membership, so it is a hard no-op for
 *       non-realestate / non-HOT / non-concierge {@code LEAD_SCORE_UPDATED} events. <strong>No live Twilio /
 *       Anthropic in tests</strong> (WireMock + a mock {@code TwilioSmsService}).</li>
 *   <li>{@code 4263-4265} — <em>Real Estate Concierge RE-3 (flagship #3)</em>: showing booking over SMS —
 *       the {@code OFFERING_SLOTS} → {@code BOOKED} states. All gated on
 *       {@code kmosf.modules.realestate.enabled}. When the buyer expresses showing intent (a cheap
 *       deterministic keyword pre-filter — no model call, so the RE-1 grounded path's call count is
 *       byte-identical) the {@code ShowingBookingService} offers demo-grade candidate slots over SMS; on the
 *       buyer's pick it <strong>writes a showing {@code Meeting} projection DIRECTLY</strong> (the
 *       {@code CalComWebhookService.reconcileUpsert} shape — tenant-scoped, the listing address as
 *       {@code location}, the buyer attendee, the chosen {@code start}/{@code end}, {@code calComBookingUid}
 *       left null), advances the conversation to {@code BOOKED}, logs a best-effort {@code Activity(MEETING)},
 *       emits {@code SHOWING_BOOKED}, and texts the confirmation. <strong>No live Cal.com call</strong> (§7);
 *       production flips to a live Cal.com booking + the shipped {@code CalComWebhookService} reconcile
 *       (idempotent on {@code calComBookingUid}) with no concierge change. <strong>RE-3 mints only advisory
 *       codes</strong> (every path is best-effort — a Meeting-write/SMS failure never drops the conversation):
 *       {@code 4263} no availability to offer (logged, the buyer gets a graceful "your agent will reach out"
 *       note, never thrown); {@code 4264} slot already taken / booking conflict (the double-pick guard —
 *       an already-booked conversation re-confirms its existing {@code Meeting}, never a second write; logged,
 *       never thrown); {@code 4265} reserved for RE-3 growth. Reused (NOT re-allocated): {@code 2530-2532}
 *       (Twilio SMS recipient/send/secret — the reused {@code TwilioSmsService} on the offer/confirmation/
 *       re-offer sends, best-effort); the {@code Meeting}/Cal.com projection shape + the {@code Activity}
 *       {@code MEETING} type. The slot-offer + confirmation copy is deterministic templating (no Anthropic
 *       call). <strong>No live Twilio / Cal.com in tests</strong> (a mock {@code TwilioSmsService} + a direct
 *       Meeting projection write — no Cal.com).</li>
 *   <li>{@code 4266-4269} — <em>Real Estate Concierge RE-4 (flagship #3)</em>: the <strong>Marketing
 *       Studio</strong> — one agent action on a {@code Listing} → Claude (Sonnet) drafts MLS remarks + N
 *       platform-tuned social captions + an email blast, and the UNCHANGED {@code AiVisionService.extract}
 *       captions the listing photos (feature callouts woven into the copy), behind a <strong>Fair-Housing
 *       guardrail</strong> (a strict system-prompt instruction + a deterministic post-generation
 *       {@code FairHousingLint}) and a <strong>mandatory human approval before publish</strong> (the draft
 *       lands DRAFTED and is <strong>NEVER auto-published</strong> — the GBP review-reply draft→approve
 *       posture). All gated on {@code kmosf.modules.realestate.enabled}. The photo bytes are read back via
 *       the new {@code FileStorageService.getBytes} (the read-twin of {@code putBytes}); intake stores them
 *       via {@code putBytes} + a generic LISTING {@code Attachment} + a {@code ListingPhoto}.
 *       <strong>RE-4 mints only advisory codes</strong> (every path is best-effort — a Claude/vision
 *       failure never errors, it yields a partial/empty draft): {@code 4266} no listing photos to caption
 *       (advisory log — text-only generation proceeds, never thrown); {@code 4267} reserved — the
 *       Fair-Housing lint is non-blocking (it surfaces flags on the DRAFTED draft for human review; it does
 *       NOT hard-block, because the mandatory human approval is the real gate, never an auto-publish);
 *       {@code 4268} generation degraded/blank — a Claude budget/upstream/parse failure (the draft is saved
 *       DRAFTED with {@code generationDegraded=true} + a flag, never thrown); {@code 4269} reserved for RE-4
 *       growth. Reused (NOT re-allocated): {@code 1200-1203} (AI budget gate / Anthropic non-200 /
 *       missing-key — via the {@code MarketingGenerationService} sibling of {@code ConciergeAnswerService}
 *       AND the shared {@code AiVisionService.extract}; all best-effort, swallowed into a degraded draft);
 *       {@code 4211} unsupported image media type on photo intake (the shared
 *       {@code AiVisionService.isSupportedMediaType} gate, the HS-2 {@code 4211} posture);
 *       {@code 4253} listing not found / not owned (the reused RE-1 {@code ListingService} not-found code) —
 *       also reused on the draft surface for a missing draft (404) and a non-DRAFTED approve/skip (409, the
 *       {@code GbpReviewReplyAdminService} {@code 4033} same-status guard, kept RE-local);
 *       {@code 1310}/{@code 1311} file storage (the reused {@code FileStorageService} {@code putBytes} store
 *       + the new {@code getBytes} read, including the {@code 1311} foreign-tenant-key guard);
 *       {@code 1130}/{@code 1132} module gate; {@code 1800} STAFF {@code RoleGuard} on the controller. The
 *       {@code ListingMarketingController} is {@code @ConditionalOnProperty}-gated, so it is absent from the
 *       generated OpenAPI spec when the module is off. <strong>No live Anthropic / OpenAI / Atlas in
 *       tests</strong> (WireMock for the text + vision Anthropic calls; a test {@code FileStorageService}
 *       stub for the photo bytes). <strong>RE-1/RE-2/RE-3 + ChairFill + the scorer byte-equivalent:</strong>
 *       RE-4 is a purely additive agent-triggered surface + two additive {@code @Bean}s; it touches no
 *       inbound-SMS / concierge / scoring path.</li>
 *   <li>{@code 4270-4274} — <em>Real Estate Concierge RE-5a (flagship #3)</em>: the
 *       <strong>staff-facing concierge conversation read</strong> backing the RE-5b transcript +
 *       citation viewer + lead panel. Two {@code @ConditionalOnProperty}-gated read endpoints on
 *       {@code ConciergeConversationController} ({@code GET /realestate/conversations} list +
 *       {@code GET /realestate/conversations/{id}} detail), absent from the OpenAPI spec when the
 *       module is off (the {@code ListingController} precedent). All gated on
 *       {@code kmosf.modules.realestate.enabled}. A pure read over the RE-1..RE-3 collections — it
 *       mints only {@code 4270} conversation not found / not owned (the tenant-scoped by-id fetch, the
 *       RE-1 {@code ListingService} {@code 4253} not-found posture, 404); {@code 4271-4274} reserved for
 *       RE-5a read growth. Reused (NOT re-allocated): {@code 1130}/{@code 1132} module gate (a
 *       non-realestate tenant → the shared not-enabled response, the {@code WaitlistBoardController}
 *       1132 posture); {@code 1800} STAFF {@code RoleGuard}. The {@code leadTier} enrichment reads the
 *       buyer {@code Contact}'s {@code leadScore} (the same HOT/WARM/COLD the RE-2 hot-handoff keys on),
 *       null when the buyer is unscored / not yet materialized; a missing contact never fails the read.
 *       <strong>No external in tests</strong> (a pure Mongo-seeded read — no WireMock, no Twilio).
 *       <strong>RE-1..RE-4 + ChairFill + the scorer byte-equivalent:</strong> RE-5a is a purely
 *       additive read controller + DTOs + two additive repo finders; it touches no inbound-SMS /
 *       concierge / qualification / marketing / scoring path.</li>
 *   <li>{@code 4275-4279} — <em>FrontDesk IQ FD-1 (Health-practices flagship #4)</em>: the
 *       {@code frontdesk} module skeleton + the thin <strong>PHI-free</strong> {@code Appointment} model +
 *       the nightly no-show-risk model ({@code FrontDeskNoShowScoringService} — a parallel fork of the
 *       chairfill {@code NoShowRiskScoringService}, itself a fork of {@code LeadScoringV2Service}). The
 *       first free band above Real Estate (which ends at {@code 4274}). Whole module is
 *       {@code @ConditionalOnProperty}-gated on {@code kmosf.modules.frontdesk.enabled} (default off) +
 *       {@code Tenant.enabledModules} membership, so most paths reuse existing codes and FD-1 mints little.
 *       <strong>The headline is the PHI boundary, enforced by construction</strong> (fence F1): the
 *       {@code Appointment} document and the scorer's feature vector carry NO clinical field, so the model
 *       cannot see a diagnosis; a release-blocking IT asserts via reflection that no clinically-named field
 *       exists. New codes: {@code 4275} a no-show retrain already running for the tenant (409 — the CF-1
 *       {@code 4221} analogue in the FD band; the {@code NoShowRiskController.retrain} guard); {@code 4276}
 *       appointment not found / not owned (the tenant-scoped by-id fetch, the RE-1 {@code ListingService}
 *       {@code 4253} not-found posture, 404); {@code 4277} invalid appointment payload — a create/update
 *       missing the required {@code contactId}/{@code scheduledStart} (400, nothing to score without them);
 *       {@code 4278-4279} reserved for FD-1 growth. Reused (NOT re-allocated): {@code 1130}/{@code 1132}
 *       module gate (a non-frontdesk tenant → the shared not-enabled response, the {@code NoShowRiskController}
 *       / {@code WaitlistBoardController} 1132 posture); {@code 1800} STAFF {@code RoleGuard} on the CRUD.
 *       <strong>No outbound comms in FD-1</strong> (that is FD-2 {@code 4280-4284}). <strong>CF-1 + the
 *       lead-scorer + the core calendar + NMM/ChairFill/RE byte-equivalent:</strong> FD-1 is a purely
 *       additive module that reads only {@code AppointmentRepository}, never {@code BookingRepository} —
 *       the two no-show scorers share the {@code NoShowRisk} value type but never share data.</li>
 *   <li>{@code 4280-4284} — <em>FrontDesk IQ FD-2 (Health-practices flagship #4)</em>: risk-tiered
 *       confirmation + recall/recare re-engagement, all <strong>generic / PHI-free outbound copy</strong>
 *       (fence F3). The {@code FrontDeskConfirmationService} {@code @PostConstruct} subscriber on
 *       {@code APPOINTMENT_RISK_SCORED} (HIGH → an extra confirmation ask; LOW/MEDIUM → a light reminder;
 *       no deposit, unlike CF-2), the {@code RecallDetectorJob} nightly recall sweep (lapsed-patient →
 *       recall {@code Sequence} enroll + generic recare nudge), and the {@code FrontDeskReminderAutomation}
 *       baseline {@code WorkflowRule} seeder. <strong>FD-2 mints NO new error codes</strong> — it is a
 *       parallel {@code frontdesk} subscriber + two scheduled jobs that reuse existing codes only: AI
 *       {@code 1200-1203} (via {@code ConfirmationCopyService} — the budget gate / upstream non-200 /
 *       missing-key, all degraded best-effort to a generic template), Twilio {@code 2530-2532} (SMS send,
 *       degraded best-effort). The whole {@code 4280-4284} band is reserved for FD-2 growth.
 *       <strong>FD-1 + CF-1/CF-2 + the lead-scorer + NMM/ChairFill/RE byte-equivalent:</strong> FD-2 reads
 *       only {@code AppointmentRepository} / {@code ContactRepository} / the shipped {@code Sequence} engine,
 *       touches no salon {@code Booking} / deposit path, and the copy is provably generic (a release-blocking
 *       IT asserts a forbidden clinical/provider-token set is absent from every outbound body).</li>
 *   <li>{@code 4285-4289} — <em>FrontDesk IQ FD-3 (Health-practices flagship #4)</em>: PHI-free
 *       voicemail-to-callback. A new {@code HealthFrontDeskExtractionStrategy}
 *       ({@code voicemailVertical="health-frontdesk"}) reuses the HS-1 voicemail seam end-to-end
 *       ({@code VoicemailExtractionStrategyResolver} + {@code VoicemailExtractionService} +
 *       {@code TwilioVoicemailService}) to turn an after-hours health-practice voicemail into a
 *       <strong>logistics-only</strong> extraction (name / callback number / intent bucket — never a
 *       symptom/diagnosis/medication) and a front-desk callback {@code Activity(CALL, INBOUND)} +
 *       best-effort notify. <strong>FD-3 mints NO new error codes</strong> — it reuses AI
 *       {@code 1200-1203} (the {@code VoicemailExtractionService} budget gate / upstream non-200 /
 *       missing-key, all degraded best-effort so a callback is never dropped), Twilio signature
 *       {@code 4000-4003} (the {@code TwilioVoicemailService} verify, unchanged), and {@code 1300}
 *       Activity-create. The whole {@code 4285-4289} band is reserved for FD-3 growth.
 *       <strong>The headline is the PHI boundary, enforced by construction (fence F2):</strong> the
 *       single divergence from the shipped seam is the additive default-{@code true}
 *       {@code VoicemailExtractionStrategy.persistTranscript()} bit — mole + multi-trade do not
 *       override it (so {@code TwilioVoicemailIT} / {@code HomeServicesVoicemailIT} stay
 *       byte-equivalent), while the health strategy returns {@code false} so the raw transcript is
 *       NEVER stored on {@code Activity.body} (a redaction marker is) and the recording pointer is
 *       omitted; a release-blocking IT asserts the stored health-voicemail Activity contains none of
 *       the raw transcript text and no clinical token. NMM / Home-Services / ChairFill / Real Estate
 *       are byte-equivalent.</li>
 *   <li>{@code 4290-4294} — <em>FrontDesk IQ FD-4 (Health-practices flagship #4)</em>: the
 *       <strong>HIPAA-safe review-reply</strong> (the flagship's signature demo). A
 *       {@code FrontDeskReviewReplyService} (the CF-4 {@code SalonReviewReplyService} sibling) drafts a public
 *       reply via the <strong>unchanged</strong> {@code GbpReplyDraftService.draftReply(review, prompt,
 *       exemplars)} overload using a <strong>HIPAA-guardrail system prompt</strong> (thank / apologize /
 *       invite-offline — NEVER confirm the reviewer was a patient, name a procedure/treatment/diagnosis/
 *       medication, or echo a clinical term the review itself raised, fence F4), runs a deterministic
 *       {@code HipaaReplyLint} over the draft (the RE-4 {@code FairHousingLint} / FD-2 F3-lint backstop —
 *       surfaces residual patient-status / clinical phrases, never blocks), and parks it DRAFTED in the shared
 *       {@code GbpReviewReply} queue, exposed through a STAFF-gated draft → approve(copy-ready)/skip queue
 *       ({@code FrontDeskReviewReplyController} — {@code POST /frontdesk/reviews/draft},
 *       {@code GET /frontdesk/reviews}, {@code POST /{id}/approve}|{@code /{id}/skip}). <strong>Never
 *       auto-posted</strong> (the GBP posture); approval is copy-ready (no live Google call — the demo path,
 *       no GBP OAuth). New codes: {@code 4290} a malformed paste-in (blank review text, 400); {@code 4291} the
 *       draft is not DRAFTED — cannot approve/skip (the same-status guard, 409); {@code 4292} the draft was
 *       not found for the tenant (404); {@code 4293-4294} reserved for FD-4 growth. Reused (NOT re-allocated):
 *       the AI band {@code 1200-1203} (via the unchanged {@code GbpReplyDraftService} — budget / upstream
 *       non-200 / missing-key, degraded best-effort to a generic HIPAA-safe fallback so a review is never
 *       dropped and a leaky reply is never persisted); {@code 1130}/{@code 1132} module gate (a non-frontdesk
 *       tenant → the shared not-enabled response, the {@code NoShowRiskController} posture); {@code 1800} STAFF
 *       {@code RoleGuard}. <strong>{@code GbpReplyDraftService} + NMM / GBP + ChairFill review-reply
 *       byte-equivalent:</strong> FD-4 passes an additive per-call prompt; it does NOT modify
 *       {@code GbpReplyDraftService}, the shared {@code GbpReviewReply} model, or the GBP admin surface, so the
 *       GBP draft-reply ITs and {@code SalonReviewReplyIT} pass unchanged.</li>
 *   <li>{@code 4295-4299} — <em>FrontDesk IQ FD-5a (Health-practices flagship #4)</em>: the two
 *       <strong>staff-facing board reads</strong> backing the FD-5 recall board + callback inbox FE
 *       ({@code FrontDeskBoardController} — {@code GET /frontdesk/recall} + {@code GET /frontdesk/callbacks}).
 *       The other two FD-5 surfaces already have their reads (the risk-sorted day view =
 *       {@code NoShowRiskController GET /frontdesk/risk/appointments}; the review inbox =
 *       {@code FrontDeskReviewReplyController GET /frontdesk/reviews}). A pure read over the FD-2
 *       {@code RecallLog} / FD-1 {@code Appointment} / FD-3 callback {@code Activity} collections: the
 *       recall board surfaces the FD-2 lapsed-contact selector (most-recent visit older than the recall
 *       window AND no upcoming appointment, + the contact name + the nudged-this-period flag), and the
 *       callback inbox surfaces the FD-3 PHI-free voicemail callbacks (fence F2 — logistics fields ONLY,
 *       NEVER a transcript: the read keys on the {@code TRANSCRIPT_REDACTED_MARKER} body produced only by
 *       the health front-desk strategy, and the {@code CallbackInboxItemDTO} has no transcript/body/
 *       recording field). <strong>FD-5a mints NO new error code</strong> — it reuses the shared
 *       {@code TenantModuleRegistry.requireEnabled} module gate ({@code 1130}/{@code 1132}, the
 *       {@code 4202}/{@code 2700}/{@code 3930} not-enabled posture) and the {@code RoleGuard} STAFF gate
 *       ({@code 1800}). The band {@code 4295-4299} is RESERVED for future board-read growth. The
 *       {@code FrontDeskBoardController} is {@code @ConditionalOnProperty}-gated, so it is absent from the
 *       generated OpenAPI spec when the module is off (the {@code NoShowRiskController} /
 *       {@code WaitlistBoardController} / {@code ConciergeConversationController} precedent).
 *       <strong>FD-1..FD-4 + ChairFill + NMM byte-equivalent:</strong> FD-5a is a purely additive read
 *       controller + DTOs + two additive repo finders + a visibility-only constant promotion; it touches
 *       no scoring / outbound-comms / voicemail-pipeline / review-reply behavior.</li>
 *   <li>{@code 4300-4319} — <em>Nurture / Cadence Engine (E1)</em>: the keystone shared reactivation
 *       engine ({@code model/nurture}, {@code service/nurture}, {@code controller/nurture}). Segments a
 *       tenant's dormant contacts into vertical-agnostic dormancy buckets (thresholds carried by the
 *       campaign — never hardcoded), runs a default-OFF multi-touch SMS+email cadence with per-step
 *       backoff (the {@code NurtureRunner} — {@code kmosf.modules.nurture-runner.enabled}
 *       matchIfMissing=false, the {@code CoverageNudgeJob}/{@code GbpReviewPoller} default-OFF posture;
 *       per-(enrollment, step) ledger-insert-FIRST over the unique {@code tenant_enrollment_step_idx} so
 *       a restart/concurrent tick sends ZERO duplicate, NEVER {@code switchIfEmpty(send)}), and
 *       exits-and-books on a positive reply (per-tenant Cal.com booking link from
 *       {@code IntegrationConnection(twilio).config["bookingLink"]} over SMS — NO live Cal.com call).
 *       New codes: {@code 4300} nurture not enabled for the tenant (404 — the in-range parity code; the
 *       admin controller actually surfaces the shared {@code 1130}/{@code 1132} via
 *       {@code requireEnabled}); {@code 4301} NurtureCampaign not found (404); {@code 4302} campaign
 *       inactive — cannot segment/enroll (409); {@code 4303} invalid campaign definition — no segments,
 *       no steps, non-contiguous stepIndex, or a step missing its channel's template (400); {@code 4310}
 *       NurtureEnrollment not found / no active enrollment for the reply (404, genuine
 *       {@code switchIfEmpty}); {@code 4311} enrollment already terminal — a second positive-reply is a
 *       no-op (409, explicit boolean); {@code 4312} reply ref invalid — blank contact phone (400). The
 *       band {@code 4313-4319} is RESERVED for nurture growth. Reused (NOT re-allocated): {@code 1130}/
 *       {@code 1131}/{@code 1132} (the module gate via {@code requireEnabled}), {@code 1200}-{@code 1203}
 *       (AI budget / Anthropic upstream / missing-key — surfaced best-effort by the message composer,
 *       which degrades to the template rather than dropping the send), {@code 2530}-{@code 2532} (Twilio
 *       SMS), {@code 1300} (Activity via the unchanged {@code ActivityCrudService}), {@code 1800}
 *       (RoleGuard ADMIN), {@code 3100} ({@code @IdempotentRoute} missing key). Consent is the
 *       {@code sms-opt-out} tag (a skip, not an error). The {@code NurtureCampaignController} is
 *       {@code @ConditionalOnProperty}-gated (matchIfMissing=true), so it is absent from the generated
 *       OpenAPI spec when the module is off; the default-OFF runner means no live send in CI / any
 *       default run. <strong>Strictly additive — the shipped Sequence engine, InboundSmsService,
 *       TwilioSmsService, EmailService, and AnthropicAiAssistService are empty-diff vs {@code main}.</strong></li>
 *   <li>{@code 4320-4339} — <em>Inbound Responder + Intent Router (E2)</em>: the reusable inbound-SMS
 *       responder engine (a generic free-text intent classifier + a pluggable {@code IntentHandler}
 *       registry + lightweight conversation state). The {@code responder} module is gated
 *       {@code @ConditionalOnProperty(kmosf.modules.responder.enabled, matchIfMissing=true)}; per-tenant
 *       membership is enforced by {@code TenantModuleRegistry.requireEnabled("responder")} (1130/1132) in
 *       {@code ResponderConfigController}. The generic inbound delegation only ACTIVATES for a tenant with
 *       a usable {@code ResponderConfig} — absent/disabled/empty config makes the {@code InboundIntentRouter}
 *       return {@code IGNORED}, so the shipped {@code InboundSmsService} STOP/YES/realestate path is
 *       byte-identical (the regression-gate invariant). New codes: {@code 4320} responder not enabled for
 *       the tenant (404 — the in-range parity echo of the {@code requireEnabled} gate; the {@code 4250}/
 *       {@code 4300}/{@code 2700}/{@code 3930} not-enabled posture); {@code 4321} responder config not
 *       found for the tenant on a read (404 — a genuine {@code switchIfEmpty}); {@code 4322} invalid
 *       responder config — an intent with a blank name (400). The band {@code 4323-4339} is RESERVED for
 *       responder growth. Reused (NOT re-allocated): {@code 1130}/{@code 1131}/{@code 1132} (the module
 *       gate), {@code 1200}-{@code 1203} (AI budget / Anthropic upstream / missing-key — surfaced
 *       best-effort by the {@code InboundIntentClassifier}, which degrades to {@code UNKNOWN}+handoff
 *       rather than throwing), {@code 2530}-{@code 2532} (Twilio SMS — the reply + handoff-notify paths),
 *       {@code 1300} (Activity, if a handler logs one), {@code 1800} (RoleGuard ADMIN on the config
 *       controller), {@code 3100} ({@code @IdempotentRoute} missing key on test-classify), {@code 4000}/
 *       {@code 4001} (the inbound webhook's Twilio signature / not-connected — owned by the unchanged
 *       {@code InboundSmsService}). Consent is the {@code sms-opt-out} tag (a skip, not an error). The
 *       {@code ResponderConfigController} is {@code @ConditionalOnProperty}-gated (matchIfMissing=true);
 *       the classifier is WireMock-able and the reply/notify SMS+email are mocked in ITs (no live send in
 *       CI). <strong>The ONLY pre-existing service touched is {@code InboundSmsService} (an additive
 *       IGNORED-fallthrough delegation); {@code TwilioSmsService}, {@code WaitlistClaimService},
 *       {@code ConciergeInboundRouter}, and the AI transports are empty-diff vs {@code main}.</strong></li>
 *   <li>{@code 4600-4619} — <em>"Get Paid" AR / collections</em>: tiered overdue-invoice dunning over
 *       the existing billing core ({@code module/ar}). The {@code ar} module is gated
 *       {@code @ConditionalOnProperty(kmosf.modules.ar.enabled, <strong>matchIfMissing=false</strong>)}
 *       — DEFAULT-OFF (a money / customer-facing-comms module; the {@code GbpReviewPoller} /
 *       {@code CoverageNudgeJob} default-OFF posture), so a non-AR tenant gets no aging sweep, no
 *       SENT→OVERDUE transition, and no dunning — byte-identical to before this module existed. The
 *       default-OFF {@code ArAgingSweepJob} sweeps each tenant's SENT/OVERDUE invoices past
 *       {@code dueAt} (+ grace), and for each crossed tier (≥3→D3, ≥7→D7, ≥14→D14) NOT already in the
 *       {@code DunningLog} it inserts the ledger row FIRST over the unique {@code tenant_invoice_tier_idx}
 *       ({@code onErrorResume(DuplicateKeyException → empty)} so a restart/concurrent tick fires ZERO
 *       duplicate, NEVER {@code switchIfEmpty(create)}), flips the invoice SENT→OVERDUE on the first
 *       tier-insert (via the unchanged {@code InvoiceService.setStatus}), and emits the matching
 *       advisory {@code INVOICE_OVERDUE_{D3,D7,D14}} event.
 *       <strong>New codes (AR-4):</strong>
 *       {@code 4600} ar not enabled for the tenant (404 — the in-range parity code; the
 *       {@code 4250}/{@code 4300}/{@code 4320}/{@code 2700}/{@code 3930} not-enabled posture; the AR-4
 *       read controller surfaces the shared {@code 1130}/{@code 1132} via {@code requireEnabled});
 *       {@code 4601} invoice not found when creating a promise-to-pay — the tenant-scoped
 *       {@code InvoiceRepository.findByTenantIdAndId} returned empty (404 — a genuine
 *       {@code switchIfEmpty} for the entity-not-found case in {@code ArAgingController.createPromise});
 *       {@code 4602} invalid promise-to-pay request — {@code promisedDate} is in the past,
 *       {@code promisedAmount} ≤ 0, or a required field is missing (400). The band {@code 4603-4619}
 *       is RESERVED for AR growth. Reused (NOT re-allocated):
 *       {@code 1130}/{@code 1131}/{@code 1132} (the module gate via {@code requireEnabled}), the
 *       existing invoice/billing codes via the unchanged {@code InvoiceService} ({@code 2300}/
 *       {@code 2301}/{@code 3640}). <strong>Strictly additive — the shipped {@code Invoice},
 *       {@code InvoiceService}, {@code StripeCheckoutService}, {@code RuleActionDispatcher},
 *       {@code TwilioSmsService}, and {@code AnthropicAiAssistService} are empty-diff vs {@code main}
 *       (the only pre-existing edits are an additive {@code InvoiceRepository} finder + additive
 *       {@code DomainEventType} constants + the AR-4 additive guard in
 *       {@code DunningDispatchService}).</strong></li>
 *   <li>{@code 4620-4639} — <em>AI Proposal / SOW generator</em>: from a few lines of discovery notes,
 *       Claude (Sonnet) drafts a scoped, line-item-<strong>priced</strong> SOW ({@code module/proposals}).
 *       "A SOW is a priced {@code Quote} with prose" — the priced line items + computed totals are a DRAFT
 *       {@code Quote} created via the UNCHANGED {@code QuoteService.create}, and the four narrative sections
 *       (scope / deliverables / assumptions / timeline) are an additive {@code SowDraft} document linked by
 *       {@code quoteId} (the shipped {@code Quote} model stays empty-diff vs {@code main}). The
 *       {@code proposals} module is gated
 *       {@code @ConditionalOnProperty(kmosf.modules.proposals.enabled, <strong>matchIfMissing=false</strong>)}
 *       — DEFAULT-OFF (a day-one productization bet; the AR / {@code GbpReviewPoller} default-OFF posture),
 *       so a non-proposals tenant gets no draft service and no {@code /proposals/**} routes — byte-identical
 *       to before this module existed. {@code ProposalDraftService} calls the reused
 *       {@code AnthropicAiAssistService}-shaped transport (per-tenant Anthropic key + house-key fallback,
 *       {@code AiUsageRecorder} budget gate, WireMock-able base-url — the {@code VoicemailExtractionService}
 *       sibling shape; that AI core stays empty-diff) with a strict system prompt → ONLY JSON
 *       {@code {lineItems:[{description,quantity,unitPrice}], scope, deliverables, assumptions, timeline}};
 *       the parse is <strong>defensive</strong> (strips code fences / prose; a blank / over-budget /
 *       upstream-error / unparseable answer degrades to an empty draft with {@code aiApplied=false} — it
 *       NEVER throws, so an AI outage never blocks a SOW draft; "AI is triage, not truth"). The parsed
 *       line items materialize a DRAFT {@code Quote} (the existing {@code computeTotals} prices it; never
 *       finalized) + the prose persists to {@code SowDraft}; an advisory {@code PROPOSAL_DRAFTED} fires.
 *       <strong>New codes:</strong>
 *       {@code 4620} proposals module not enabled for the tenant (404 — the in-range parity code; the
 *       {@code 4600}/{@code 4250}/{@code 4220}/{@code 2700}/{@code 3930} not-enabled posture; the
 *       {@code ProposalDraftController}'s per-tenant membership check surfaces this in-band, while the
 *       {@code @ConditionalOnProperty} gate also makes the routes absent (404) when the deployment flag is
 *       off — defense in depth);
 *       {@code 4621} invalid draft request — blank {@code notes} or {@code notes} longer than
 *       {@code kmosf.modules.proposals.max-notes-chars} (default 8000) (400). The band {@code 4622-4639}
 *       is RESERVED for proposals growth (SOW-3 send-to-sign reuses the existing {@code ContractService} /
 *       Documenso {@code 37xx} codes; SOW PDF reuses the existing {@code QuotePdfService} — no new codes).
 *       Reused (NOT re-allocated): {@code 1200}-{@code 1203} (AI budget / Anthropic upstream / missing-key
 *       — surfaced best-effort by {@code ProposalDraftService}, which degrades to an empty {@code aiApplied=false}
 *       draft rather than throwing), {@code 1130}/{@code 1131}/{@code 1132} (the shared module-load /
 *       tenant-not-found checks via {@code TenantModuleRegistry}), {@code 1800} (RoleGuard STAFF on the
 *       controller), {@code 2200} (Quote-not-found on the {@code GET /proposals/{id}} read via the unchanged
 *       {@code QuoteService}). <strong>Strictly additive — the shipped {@code Quote}, {@code LineItem},
 *       {@code QuoteService}, {@code QuotePdfService}, {@code ContractService}, {@code AnthropicAiAssistService},
 *       and {@code AiUsageRecorder} are empty-diff vs {@code main} (the only pre-existing edits are additive
 *       {@code DomainEventType} constant + this Javadoc + additive {@code application.properties} doc +
 *       the imports-file entry).</strong></li>
 *   <li>{@code 4340-4349} — <em>Review Engine (E3)</em>: post-visit review-REQUEST delivery + sentiment
 *       triage + per-entity insights, extending the shipped {@code integration/gbp} review pipeline
 *       additively. On a completed visit/job ({@code BOOKING_COMPLETED} salon + {@code MILESTONE_COMPLETED}
 *       home/project) {@code ReviewRequestService} creates a {@code ReviewRequest} (explicit-boolean over
 *       the unique {@code tenant_subject_contact_idx}); a <strong>default-OFF</strong>
 *       {@code ReviewRequestSenderJob} ({@code @ConditionalOnProperty(kmosf.modules.review-engine.sender-enabled,
 *       matchIfMissing=false)} — the {@code CoverageNudgeJob}/{@code ImapInboundPoller} posture) sends due
 *       PENDING requests as a <strong>frictionless, no-incentive</strong> Google-review SMS (Google's 2026
 *       policy bans incentives), opt-out-aware ({@code sms-opt-out}), frequency-capped, atomic-claim
 *       idempotent. {@code ReviewSentimentService} (a NEW sibling mirroring {@code GbpReplyDraftService} —
 *       that core is empty-diff) classifies each ingested review (rating-first always; the AI refinement
 *       is opt-in {@code kmosf.review-engine.ai-refine-enabled}, default-OFF) on the one surgical
 *       {@code GbpReviewPoller} seam and stores the additive {@code sentiment}/{@code sentimentSource}; a
 *       negative ({@code rating <= threshold} OR {@code NEGATIVE}) fires a best-effort manager alert when
 *       the opt-in {@code kmosf.review-engine.negative-alert-enabled} (default-OFF) is set. Both opt-ins
 *       are default-OFF so the seam adds ZERO extra Anthropic calls and ZERO extra notify in the default
 *       path (the existing {@code GbpReviewPollerIT} stays byte-identical). The generic
 *       {@code ReviewInsightsService} +
 *       ADMIN {@code ReviewInsightsController} ({@code GET /gbp/review-insights[/{subjectType}/{subjectId}]})
 *       aggregate per {@code (subjectType, subjectId)} + per-tenant. New codes: {@code 4340} review-insights
 *       subject type invalid (400 — an unparseable {@code {subjectType}} path segment). The band
 *       {@code 4341-4349} is RESERVED for review-engine growth. Reused (NOT re-allocated): {@code 1200}-
 *       {@code 1203} (AI budget / Anthropic upstream / missing-key — surfaced best-effort by
 *       {@code ReviewSentimentService}, which degrades to the rating-based result rather than throwing),
 *       {@code 2530}-{@code 2532} (Twilio SMS — the request-send + negative-alert paths), {@code 1800}
 *       (RoleGuard ADMIN on the insights controller). The insights controller is
 *       {@code @ConditionalOnProperty(kmosf.modules.gbp-reviews, matchIfMissing=true)} + ADMIN — the
 *       {@code GbpReviewReplyAdminController} precedent, NOT {@code requireEnabled} ({@code gbp-reviews} is
 *       an {@code integration/} package, not a registered module — E3 deviation D1). The sender is default-OFF
 *       (no live request SMS in CI); the sentiment Anthropic call is WireMock-able; the request-send +
 *       negative-alert SMS/email are mocked in ITs (no live send). <strong>The ONLY pre-existing service
 *       touched is {@code GbpReviewPoller} (a surgical additive sentiment-store + negative-alert seam in
 *       {@code draftAndFinish}, all {@code onErrorResume}'d); {@code GbpReviewReply} gains 2 additive nullable
 *       fields; {@code GbpReplyDraftService}, {@code GbpReviewReplyAdminService}, {@code GbpApiClient},
 *       {@code TwilioSmsService}, {@code EmailService}, {@code AnthropicAiAssistService},
 *       {@code SalonBookingService}, and {@code MilestoneService} are empty-diff vs {@code main}.</strong></li>
 *   <li>{@code 4350-4359} — <em>Gap-Fill Waitlist engine (E4)</em>: the vertical-agnostic gap-fill
 *       waitlist engine ({@code model/waitlist}, {@code service/waitlist}, {@code controller/waitlist},
 *       {@code repository/waitlist}). On a freed slot a consumer calls
 *       {@code GapFillEngine.gapFill(tenantId, slot)} → {@code WaitlistRankingService} ranks the OPEN,
 *       opted-in, slot-matching {@code WaitlistEntry} pool by inverted show-risk (most-likely-to-show
 *       first; rules-based + FIFO tiebreak, over entry-carried stats — NO salon Booking coupling) → the
 *       top-N get a time-boxed offer SMS + a {@code WaitlistOffer} ledger row. The first inbound YES
 *       atomically claims the slot via {@code WaitlistClaimEngine} (a {@code findAndModify} on
 *       {@code waitlist_slot_claims} with the {@code claimedByContactId:null} guard + {@code upsert} —
 *       the CF-3 D2 / {@code WorkOrderNumberGenerator} double-YES-correct gate; winner → the consumer's
 *       {@code SlotMaterializer} creates the real domain record, loser → an apology); offer-expiry is the
 *       {@code WaitlistOfferExpiryService} ledger-hygiene sweep (no {@code @Scheduled} live job by default
 *       — the engine is consumer-triggered + dormant). New codes: {@code 4350} waitlist entry not found
 *       for the tenant (404); {@code 4351} waitlist entry invalid (blank {@code contactId} on create, 400);
 *       {@code 4352} gap-fill slot request invalid (missing {@code slotKey} / {@code slotStart}, 400). The
 *       band {@code 4353-4359} is RESERVED for waitlist-engine growth. Reused (NOT re-allocated):
 *       {@code 1130}/{@code 1132} (module gate — the {@code waitlist} module on the admin controller, the
 *       {@code NurtureCampaignController} posture); {@code 2530}-{@code 2532} (Twilio SMS — the offer /
 *       confirmation / apology send paths); {@code 1800} (RoleGuard ADMIN on the admin controller). The
 *       engine has <strong>no AI dependency</strong> (deterministic template copy) so it adds ZERO
 *       Anthropic calls; {@code TwilioSmsService} is mocked in ITs (no live send). <strong>ChairFill is
 *       byte-equivalent</strong> — the engine is a parallel generic package; the chairfill
 *       {@code GapFillWaitlistIT} regression gate stays green and {@code InboundSmsService} /
 *       {@code TwilioSmsService} / {@code NoShowRiskScoringService} are empty-diff vs {@code main}.</li>
 *   <li>{@code 4360-4369} — <em>RE "Database Goldmine" (T1)</em>: deploys the shipped E1 Nurture/Cadence
 *       engine to real estate ({@code module/realestate/nurture}). Dormant real-estate leads are
 *       auto-segmented A/B/C/D (the vertical-agnostic {@code NurtureSegmentationService}, thresholds on the
 *       RE campaign — no engine hardcoding) and run through tiered SMS/email cadences with backoff (the
 *       default-OFF {@code NurtureRunner}); a positive reply exits the enrollment and offers a showing via
 *       the booking-link SMS path ({@code NurtureReplyService}, no live Cal.com), wired through the E2
 *       responder seam by an additive {@code RealEstateNurtureReplyHandler} ({@code IntentHandler}); a
 *       per-segment ROI funnel is exposed by {@code RealEstateNurtureController} (reusing
 *       {@code NurtureAnalyticsService}). <strong>The headline correctness property is the Fair-Housing
 *       guardrail:</strong> every outbound nurture message — AI-personalized AND template — is screened by
 *       {@code FairHousingCopyFilter} (the additive {@code NurtureCopyFilter} SPI on the shared
 *       {@code NurtureMessageComposer}) which reuses the shipped {@code FairHousingLint}; flagged copy is
 *       <strong>replaced with a vetted safe template</strong> (never sent non-compliant, never dropped) and
 *       logged with code {@code 4360} (an advisory marker — the substitution is silent+safe, not a thrown
 *       HTTP error). {@code 4361-4369} RESERVED for RE-nurture growth. Module gate requires <strong>both</strong>
 *       {@code kmosf.modules.realestate} AND {@code kmosf.modules.nurture}
 *       ({@code RealEstateNurtureAutoConfiguration} composes the realestate {@code @ConditionalOnProperty}
 *       with {@code @ConditionalOnBean(NurtureMessageComposer)} + per-tenant
 *       {@code TenantModuleRegistry.requireEnabled} for both keys). Reused (NOT re-allocated):
 *       {@code 4301}/{@code 4302}/{@code 4303} (nurture campaign not-found / inactive / invalid),
 *       {@code 4310} (reply-no-active-enrollment — swallowed by the handler), {@code 1130}/{@code 1132}
 *       (module gate), {@code 1800} (RoleGuard ADMIN). <strong>The E1 nurture cores
 *       ({@code NurtureRunner}/{@code NurtureSegmentationService}/{@code NurtureReplyService}/
 *       {@code NurtureAnalyticsService}, all {@code model/nurture}, {@code controller/nurture},
 *       {@code NurtureAutoConfiguration}) AND the realestate cores ({@code FairHousingLint},
 *       {@code ShowingBookingService}, {@code ConciergeInboundRouter}, {@code RealEstateAutoConfiguration})
 *       stay empty-diff vs {@code main}</strong>; the lone additive E1 change is the
 *       {@code NurtureMessageComposer} {@code NurtureCopyFilter} hook (null ⇒ byte-identical; the 5 E1
 *       nurture ITs are the regression gate). The {@code NurtureRunner} stays default-OFF; Twilio/Email are
 *       mocked + Anthropic is WireMock in ITs (no live send).</li>
 *   <li>{@code 4370-4379} — <em>Health "RevenueRevive" (T2)</em>: deploys the same shipped E1 Nurture/Cadence
 *       engine to the <strong>frontdesk (health)</strong> vertical, PHI-free ({@code module/frontdesk/nurture})
 *       — the structural twin of T1. Dormant patients are auto-segmented A/B/C/D on <strong>logistics
 *       only</strong> (recency + an optional WON-deal value band on the health {@code NurtureCampaign} — no
 *       engine hardcoding, and the engine reads no clinical field) and run through tiered SMS/email cadences
 *       with backoff (the default-OFF {@code NurtureRunner}); a positive reply exits the enrollment and offers
 *       a rebook via the booking-link SMS path ({@code NurtureReplyService}, no live Cal.com), wired through
 *       the E2 responder seam by an additive {@code FrontDeskNurtureReplyHandler} ({@code IntentHandler},
 *       vertical {@code frontdesk}); a per-segment reactivation funnel is exposed by
 *       {@code FrontDeskNurtureController} (reusing {@code NurtureAnalyticsService}). <strong>The headline
 *       correctness property is the PHI-free guardrail:</strong> every outbound nurture message —
 *       AI-personalized AND template — is screened by {@code HipaaCopyFilter} (the same additive
 *       {@code NurtureCopyFilter} SPI on the shared {@code NurtureMessageComposer} that T1 added — no further
 *       E1 change) which reuses the shipped FD-4 {@code HipaaReplyLint}; a PHI-ish draft (patient-status
 *       confirmation or clinical vocabulary) is <strong>replaced with a vetted safe generic template</strong>
 *       (never sent PHI-ish, never dropped) and logged with code {@code 4370} (an advisory marker — the
 *       substitution is silent+safe, not a thrown HTTP error). {@code 4371-4379} RESERVED for health-nurture
 *       growth. Module gate requires <strong>both</strong> {@code kmosf.modules.frontdesk} AND
 *       {@code kmosf.modules.nurture} ({@code FrontDeskNurtureAutoConfiguration} composes the frontdesk
 *       {@code @ConditionalOnProperty} with {@code @ConditionalOnBean(NurtureMessageComposer)} + per-tenant
 *       {@code TenantModuleRegistry.requireEnabled} for both keys). Reused (NOT re-allocated):
 *       {@code 4301}/{@code 4302}/{@code 4303} (nurture campaign not-found / inactive / invalid),
 *       {@code 4310} (reply-no-active-enrollment — swallowed by the handler), {@code 1130}/{@code 1132}
 *       (module gate), {@code 1800} (RoleGuard ADMIN). <strong>The E1 nurture cores AND the frontdesk cores
 *       ({@code Appointment}, {@code FrontDeskNoShowScoringService}, {@code HipaaReplyLint},
 *       {@code FrontDeskAutoConfiguration}) stay empty-diff vs {@code main}</strong> — T2 needed ZERO E1
 *       edits (T1's SPI sufficed). The {@code NurtureRunner} stays default-OFF; Twilio/Email are mocked +
 *       Anthropic is WireMock in ITs (no live send).</li>
 *   <li>{@code 4380-4389} — <em>RE "Midnight Responder" (T3)</em>: the routing + completeness layer over the
 *       shipped RE concierge (RE-1..RE-5) + T1 (RE nurture) + E2 (responder) ({@code module/realestate/responder}).
 *       It does NOT rebuild RAG / qualification / scoring / booking / nurture. Three routing pieces + a demo
 *       seed: <strong>(1) tier routing on qualification</strong> — when the UNCHANGED nightly
 *       {@code LeadScoringV2Service} tiers a concierge-sourced realestate lead, a {@code TierRoutingService}
 *       ({@code LEAD_SCORE_UPDATED} subscriber, the {@code LeadHandoffService} mirror) routes <strong>HOT</strong>
 *       to the existing RE-2 hot-handoff (untouched — T3 no-ops on HOT, so exactly one handler per tier),
 *       <strong>WARM</strong> to a tenant-configured nurture campaign, and <strong>COLD</strong> to a
 *       tenant-configured long-cadence campaign; the enroll is the E1 explicit-boolean find-or-enroll over
 *       the unique {@code NurtureEnrollment.tenant_campaign_contact_idx} (never {@code switchIfEmpty(create)}),
 *       idempotent on re-fire; emits the advisory {@code CONCIERGE_TIER_ROUTED}. <strong>(2) off-listing /
 *       unknown-intent merge</strong> — when the grounded concierge cannot ground an inbound buyer SMS (no
 *       listing matched → {@code NO_LISTING}, always; or a strict {@code HANDOFF}, iff the per-tenant flag),
 *       it delegates to the E2 {@code DefaultHandoffIntentHandler} (staff notify + a vetted, static, generic
 *       "team member will follow up" reply) via an additive {@code @Nullable ResponderHandoffDelegate} seam
 *       on {@code ConciergeInboundRouter} (the {@code setConciergeRouter}/{@code setIntentRouter} setter-
 *       injection precedent; null delegate ⇒ byte-identical RE-1). <strong>(3) response-latency
 *       instrumentation</strong> — additive nullable {@code ConciergeTurn.receivedAt}/{@code latencyMs}
 *       (legacy turns null) + the {@code GET /realestate/responder/latency-stats} read (p50/p95/max + the
 *       after-hours share). Tier→campaign mapping + the handoff-delegation flag + the after-hours window are
 *       <strong>per-tenant config</strong> ({@code MidnightResponderConfig}; never hardcoded campaign ids).
 *       <strong>New codes:</strong> {@code 4380} Midnight Responder config not found for the tenant (404);
 *       {@code 4381} invalid config — a tier campaign id that does not resolve to one of the tenant's
 *       {@code NurtureCampaign}s (400); {@code 4382} RESERVED-as-advisory (a tier-route enroll into a
 *       missing/inactive campaign — logged, never thrown, the best-effort posture). {@code 4383-4389}
 *       RESERVED for Midnight-Responder growth. Module gate requires <strong>both</strong>
 *       {@code kmosf.modules.realestate} AND {@code kmosf.modules.responder}
 *       ({@code RealEstateMidnightAutoConfiguration} composes the realestate {@code @ConditionalOnProperty}
 *       with {@code @ConditionalOnBean(DefaultHandoffIntentHandler)} + per-tenant
 *       {@code TenantModuleRegistry.requireEnabled} for both keys; re-checked on the event path in
 *       {@code TierRoutingService}). Reused (NOT re-allocated): {@code 1130}/{@code 1132} (module gate),
 *       {@code 1800} (RoleGuard STAFF), {@code 2530-2532} (Twilio). <strong>The reused cores
 *       ({@code ConciergeAnswerService}, {@code LeadScoringV2Service}, {@code LeadHandoffService},
 *       {@code QualificationService}, {@code NurtureSegmentationService}/{@code NurtureReplyService},
 *       {@code InboundSmsService}, {@code InboundIntentRouter}, {@code DefaultHandoffIntentHandler},
 *       {@code RealEstateAutoConfiguration}, {@code ResponderAutoConfiguration}) stay empty-diff vs
 *       {@code main}</strong>; the only additive edits are the {@code ConciergeInboundRouter} delegate seam +
 *       the {@code ConciergeTurn} latency fields. Twilio is mocked + Anthropic is WireMock in ITs (no live
 *       send); the module is default-OFF.</li>
 *   <li>{@code 4390-4399} — <em>Health "Switchboard AI" (T4)</em>: deploys the shipped E2 inbound responder
 *       to the frontdesk (health) vertical as a logistics-only, PHI-free front-desk overflow + a
 *       clinical-message tripwire ({@code module/frontdesk/switchboard}). Two vertical {@code IntentHandler}
 *       beans (auto-discovered by the E2 {@code InboundIntentRouter}'s {@code List<IntentHandler>} — NO
 *       router edit): a {@code LogisticsIntentHandler} that answers the seven logistics intents (hours /
 *       location / accepting-new-patients / booking / reschedule / intake-form / review-request) from
 *       per-tenant {@code SwitchboardConfig} in generic PHI-free copy, and a {@code ClinicalTripwireHandler}
 *       that claims the {@code CLINICAL_SYMPTOM} intent and hands off with <strong>no transcript / clinical
 *       content retained</strong> — a redaction-only {@code Activity} (the fixed
 *       {@code SwitchboardRedaction} marker as the body, never the patient's words), best-effort staff
 *       notify, and a safe reply. The PHI fences (FD-2 fence F2 reused as a pattern): the raw body is never
 *       persisted (the E2 {@code ConversationState} has no body field; the tripwire writes only the marker),
 *       the per-tenant PHI-forbidding classifier prompt keeps {@code extractedSlots} empty for clinical
 *       messages (the last fence), and a defensive {@code HipaaReplyLint} scan is logged-only. PHI-free
 *       deflection analytics ({@code SwitchboardDeflectionLog} — {@code tenantId/category/occurredAt} only,
 *       no phone/content; a {@code RESPONDER_HANDED_OFF} subscriber records the HANDOFF category scoped to
 *       health tenants) + {@code GET /frontdesk/switchboard/deflection-stats}. <strong>New codes:</strong>
 *       {@code 4390} tripwire callback-activity write failed (advisory — best-effort, logged, never thrown);
 *       {@code 4391} switchboard config not found for the tenant (404, on a read); {@code 4392} invalid
 *       switchboard config (400). {@code 4393-4399} RESERVED for Switchboard growth. Module gate requires
 *       <strong>both</strong> {@code kmosf.modules.frontdesk} AND {@code kmosf.modules.responder}
 *       ({@code SwitchboardAutoConfiguration} composes the frontdesk {@code @ConditionalOnProperty} with
 *       {@code @ConditionalOnBean(InboundIntentRouter)} + per-tenant {@code TenantModuleRegistry
 *       .requireEnabled} for both keys; the T3 pattern). Reused (NOT re-allocated): {@code 1130}/{@code 1132}
 *       (module gate), {@code 1800} (RoleGuard ADMIN), {@code 2530-2532} (Twilio notify), {@code 1300}
 *       (Activity create), {@code 1200-1203} (the reused E2 classifier AI gate — surfaced as a degraded
 *       UNKNOWN, never an HTTP error). <strong>The reused cores ({@code InboundIntentRouter},
 *       {@code InboundIntentClassifier}, {@code DefaultHandoffIntentHandler}, {@code ConversationState*},
 *       {@code InboundSmsService}, {@code TwilioSmsService}, {@code ResponderAutoConfiguration}, and the
 *       frontdesk voicemail/transcript-suppression core) stay empty-diff vs {@code main}</strong>; the T4
 *       package is strictly additive. Anthropic is WireMock + Twilio/email are mocked in ITs (no live send);
 *       the module is default-OFF. A live Switchboard on real patient data implies PHI/BA status — the
 *       separately-priced, BAA-gated compliance tier applies; the demo runs on fictional data only.</li>
 *   <li>{@code 4400-4409} — <em>Home Services "Instant Callback" (T5)</em>: deploys the shipped E2 inbound
 *       responder to the home-services vertical as a missed-call → callback recovery loop
 *       ({@code module/homeservices/callback}). When a home-services voicemail is captured, a default-OFF
 *       {@code CallbackOfferSubscriber} (a {@code VOICEMAIL_LEAD_CREATED} bus subscriber — so
 *       {@code TwilioVoicemailService} stays empty-diff, NO seam) texts the caller an opt-in offer
 *       (immediate or scheduled callback), idempotent per CallSid via a ledger-insert-FIRST
 *       {@code CallbackOfferLog} (unique {@code tenant_callsid_idx}; never {@code switchIfEmpty(send)}). The
 *       caller's reply is routed through the E2 {@code InboundIntentRouter} to a {@code CallbackIntentHandler}
 *       (a vertical {@code IntentHandler} the router auto-discovers via {@code List<IntentHandler>} — NO
 *       router edit) that records a revenue-ranked {@code CallbackRequest} (explicit-boolean upsert, never
 *       {@code switchIfEmpty(create)}) — the deterministic {@code CallbackRevenueRanker} (job-value band &gt;
 *       urgency &gt; recency, no ML) reuses the home-services voicemail intake's DRAFT WorkOrder
 *       {@code customFields.jobValueBand}/{@code urgency}. The dispatcher reads the revenue-ranked queue
 *       ({@code GET /home-services/callbacks}, a pure DB sort on {@code tenant_status_score_idx}), claims a
 *       card ({@code POST /home-services/callbacks/{id}/dispatch}, {@code @IdempotentRoute}), and sees the
 *       missed→offered→accepted→dispatched recovery funnel ({@code GET
 *       /home-services/callbacks/recovery-stats}, over the {@code CallbackFunnelLog} ledger). Per-tenant
 *       copy is the {@code CallbackConfig} ({@code GET/PUT /home-services/callbacks/config}, ADMIN — never
 *       hardcoded business copy). <strong>New codes:</strong> {@code 4400} callback request not found
 *       (404); {@code 4401} callback config not found for the tenant (404, on a read); {@code 4402}
 *       callback not in REQUESTED state — cannot dispatch (409, explicit-boolean — a double-dispatch is a
 *       defensive 409); {@code 4403} invalid config body (400). {@code 4404-4409} RESERVED for callback
 *       growth. Module gate requires <strong>both</strong> {@code kmosf.modules.home-services} AND
 *       {@code kmosf.modules.responder} ({@code CallbackAutoConfiguration} composes the home-services
 *       {@code @ConditionalOnProperty} with {@code @ConditionalOnBean(InboundIntentRouter)} + per-tenant
 *       {@code TenantModuleRegistry.requireEnabled} for both keys; the T3/T4 pattern). The caller-facing
 *       offer SMS is additionally gated default-OFF on {@code kmosf.modules.home-callback-offer}
 *       (matchIfMissing=false — no live caller SMS in CI / any default run). Reused (NOT re-allocated):
 *       {@code 1130}/{@code 1132} (module gate), {@code 1800} (RoleGuard ADMIN on config CRUD),
 *       {@code 2530-2532} (Twilio SMS — the reused {@code TwilioSmsService} on the offer + reply paths),
 *       {@code 3100}/{@code 3101} (the {@code @IdempotentRoute} middleware on dispatch), {@code 1200-1203}
 *       (the reused E2 classifier AI gate — surfaced as a degraded UNKNOWN, never an HTTP error).
 *       <strong>The reused cores ({@code TwilioVoicemailService}, {@code InboundIntentRouter},
 *       {@code InboundIntentClassifier}, {@code DefaultHandoffIntentHandler}, {@code ConversationState*},
 *       {@code InboundSmsService}, {@code TwilioSmsService}, {@code MissedCallInboxController},
 *       {@code WorkOrderService}, {@code ResponderAutoConfiguration}, {@code HomeServicesAutoConfiguration})
 *       stay empty-diff vs {@code main}</strong>; the T5 package is strictly additive. Anthropic is WireMock
 *       + Twilio is mocked in ITs (no live send). Going live needs A2P 10DLC for the caller SMS (a separate
 *       human action — never the loop).</li>
 * </ul>
 *
 * <ul>
 *   <li>{@code 4410-4419} — <em>Salon "ReviewBoost" (T6)</em>: deploys the shipped E3 review engine
 *       (review requests + insights, PR #104) to the salon vertical as a per-stylist review dashboard
 *       ({@code module/chairfill/reviewboost}). <strong>Most of ReviewBoost already ships</strong> — the
 *       per-stylist attribution (salon {@code BOOKING_COMPLETED} carries {@code staffMemberId} →
 *       {@code ReviewRequestService} stamps the request {@code ReviewSubjectType.STAFF +
 *       staffMemberId}), the frictionless no-incentive review-request SMS ({@code ReviewRequestSenderJob},
 *       default-OFF), daily GBP sentiment triage + the negative manager alert ({@code ReviewSentimentService}
 *       + {@code ReviewNegativeAlertService}, both default-OFF), and the 1-click AI reply drafts + the
 *       approval queue ({@code GbpReplyDraftService} + the CF-4 {@code SalonReviewReplyService} +
 *       {@code GbpReviewReplyAdminController}). T6's genuine net-new is the per-stylist insights
 *       <em>list</em> read surface: {@code GET /chairfill/reviewboost/insights} returns a
 *       {@code SalonReviewBoardDTO} (tenant-wide review-content + funnel header + one
 *       {@code StylistReviewStatsDTO} per active stylist) and {@code GET /chairfill/reviewboost/config}
 *       returns a {@code ReviewBoostConfigDTO} wiring read-back — both assembled purely by reusing the
 *       unchanged {@code ReviewInsightsService} (tenant rollup + per-{@code STAFF}-subject rollup) over the
 *       active {@code StaffMember}s from {@code StaffMemberRepository}. <strong>No code is minted in this
 *       band</strong> — the read surface leans on the reused {@code 1130}/{@code 1132} (module gate, both
 *       {@code chairfill} AND {@code salon-spa} per-tenant via {@code TenantModuleRegistry.requireEnabled})
 *       and {@code 1800} (RoleGuard ADMIN); {@code 4410-4419} is RESERVED for ReviewBoost growth. Module
 *       gate: {@code @ConditionalOnProperty(kmosf.modules.chairfill)} + {@code @ConditionalOnBean
 *       (SalonBookingService)} (the ChairFill posture — salon-spa must be loaded). <strong>The reused cores
 *       ({@code ReviewInsightsService}, {@code ReviewRequestService}, {@code ReviewSentimentService},
 *       {@code ReviewNegativeAlertService}, {@code ReviewRequestSenderJob}, {@code GbpReplyDraftService},
 *       {@code SalonReviewReplyService}, {@code GbpReviewReplyAdminController}, {@code SalonBookingService},
 *       {@code Booking}, {@code StaffMember}, {@code ReviewInsights}, {@code ReviewRequest}) stay empty-diff
 *       vs {@code main}</strong>; the T6 package is strictly additive. T6 sends nothing (a read surface +
 *       demo seed); the actual sends remain E3's default-OFF jobs/services. Going live needs A2P 10DLC for
 *       the no-incentive request SMS + live GBP OAuth for posting reply drafts (separate human actions —
 *       never the loop).</li>
 *   <li>{@code 4420-4429} — <em>Health "RescheduleFlow" (T7)</em>: deploys the shipped E4 Gap-Fill Waitlist
 *       engine (PR #106) to the frontdesk (health) vertical, <strong>PHI-free</strong> — E4's first + only
 *       consumer ({@code module/frontdesk/reschedule}). On a frontdesk {@code Appointment} cancel, a
 *       {@code RescheduleGapFillSubscriber} (an {@code APPOINTMENT_CANCELLED} subscriber — the additive
 *       {@code AppointmentService.update()} emit on a real SCHEDULED|CONFIRMED -> CANCELLED transition, the
 *       {@code SalonBookingService.cancel()} precedent) projects the freed slot into a {@code WaitlistSlot}
 *       ({@code slotType="health-appt"}, {@code slotKey=<appointmentId>}) and calls the <strong>unchanged</strong>
 *       {@code GapFillEngine.gapFill} (rank → top-N time-boxed offers → generic PHI-free SMS). An inbound YES
 *       claims the slot through the <strong>unchanged</strong> {@code WaitlistClaimEngine.claim} (the atomic
 *       first-YES findAndModify — exactly-one-winner), wired via an E2 responder {@code IntentHandler}
 *       ({@code RescheduleWaitlistIntentHandler}, vertical {@code health-reschedule}) so {@code InboundSmsService}
 *       + the E2 router stay byte-equivalent; the winner's {@code FrontDeskSlotMaterializer} (a
 *       {@code SlotMaterializer} SPI bean auto-discovered by the engine) creates the real
 *       <strong>PHI-free</strong> replacement {@code Appointment} (logistics only — there is no clinical field
 *       on {@code Appointment} to set, fence F1; offer/confirm SMS is generic, never a procedure/provider).
 *       A PHI-free fill-funnel ledger ({@code RescheduleFillLog} — only {@code tenantId/event/occurredAt})
 *       backs {@code GET /frontdesk/reschedule/fill-stats}. <strong>New code:</strong> {@code 4421} (the
 *       waitlist-join body has no contactId, 400) is the lone minted HTTP code; the rest of the flow reuses
 *       the engine's codes + the module-gate {@code 1130}/{@code 1132} (both {@code frontdesk} AND
 *       {@code waitlist} per-tenant via {@code TenantModuleRegistry.requireEnabled}) + {@code 1800} (RoleGuard
 *       ADMIN). {@code 4420} + {@code 4422-4429} RESERVED for RescheduleFlow growth. Module gate:
 *       {@code @ConditionalOnProperty(kmosf.modules.frontdesk)} + {@code @ConditionalOnBean(WaitlistClaimEngine)}
 *       (the T2/T4 both-module posture — waitlist must be loaded). <strong>The reused cores
 *       ({@code GapFillEngine}, {@code WaitlistClaimEngine}, {@code WaitlistRankingService},
 *       {@code SlotMaterializer}/{@code NoOpSlotMaterializer}, {@code WaitlistOfferExpiryService}, the
 *       {@code model/waitlist/*}, the {@code WaitlistEngine*Repository}s, {@code WaitlistEngineController},
 *       {@code WaitlistAutoConfiguration}, {@code TwilioSmsService}, the E2 responder cores including
 *       {@code InboundSmsService}/{@code InboundIntentRouter}, the chairfill CF-3 {@code module/chairfill/gapfill/*},
 *       and {@code Appointment}/{@code AppointmentRepository}/{@code AppointmentController}) stay empty-diff vs
 *       {@code main}</strong>; the ONLY additive frontdesk-core edit is the minimal {@code AppointmentService}
 *       cancel-event emit (+ the {@code DomainEventType.APPOINTMENT_CANCELLED} constant). The T7 package is
 *       otherwise strictly additive. Twilio is {@code @MockitoBean}'d / the AI classifier is WireMock'd; no
 *       live external in the loop. Going live needs A2P 10DLC for the patient-facing offer/confirm SMS; a live
 *       RescheduleFlow on real patient appointment data implies PHI/BA status — the separately-priced,
 *       BAA-gated compliance tier applies (demo on fictional data only) — separate human steps, never the
 *       loop.</li>
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
