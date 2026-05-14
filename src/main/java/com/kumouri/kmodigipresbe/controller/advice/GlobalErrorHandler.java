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
 *   <li>{@code 1700-1799} — <em>Phase 9c reserved</em>: transactional email failures</li>
 *   <li>{@code 1800-1899} — <em>Phase 9a</em>: audit/compliance (RoleGuard 1800,
 *       audit query param validation 1801). The original plan §8 listed 1400-1499 for
 *       audit/compliance, but {@code DealCrudService} ships 1400/1401 since Phase 1 —
 *       audit codes were shifted to 1800-1899 to avoid renumbering merged code.</li>
 *   <li>{@code 1900-1999} — Phase 6 automation (WorkflowRule 1900, WebhookSubscription
 *       1910). The plan's original AI slot would have collided here too.</li>
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
