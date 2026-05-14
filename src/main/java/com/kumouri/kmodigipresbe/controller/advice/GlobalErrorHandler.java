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
