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
 *       (409, defensive). Reused (not re-allocated): {@code 1300} invalid RRULE
 *       (owned by {@code Rfc5545RecurringSchedule}); {@code 2300} Invoice not found;
 *       {@code 2510}/{@code 2511} Stripe webhook connection/secret; {@code 2800-2802}
 *       QBO; {@code 3100}/{@code 3101} idempotency.</li>
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
