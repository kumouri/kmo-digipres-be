package com.kumouri.kmodigipresbe.config;

import com.kumouri.kmodigipresbe.exceptions.DigiPresBeException;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotencyKey;
import com.kumouri.kmodigipresbe.model.idempotency.IdempotentRoute;
import com.kumouri.kmodigipresbe.repository.IdempotencyKeyRepository;
import com.kumouri.kmodigipresbe.tenancy.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.springframework.boot.autoconfigure.security.SecurityProperties;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.result.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

/**
 * WebFlux filter that enforces idempotency for endpoints annotated with
 * {@link IdempotentRoute}.
 *
 * <h2>Ordering</h2>
 * Runs at {@link SecurityProperties#DEFAULT_FILTER_ORDER} + 2, which is immediately
 * after {@link TenantWebFilter} (+1). This ensures the tenant context is available
 * when the filter checks for an existing key.
 *
 * <h2>Request-body safety</h2>
 * The idempotency key is the {@code Idempotency-Key} header value — the request body
 * is never buffered or read by this filter. Only the response is teed via a
 * {@link ServerHttpResponseDecorator}.
 *
 * <h2>Error codes (Phase A allocation)</h2>
 * <ul>
 *   <li>{@code 3100} — {@code Idempotency-Key} header missing on an
 *       {@link IdempotentRoute}-annotated endpoint</li>
 *   <li>{@code 3101} — concurrent duplicate request race (MongoDB unique index
 *       violation on first-insert attempt)</li>
 * </ul>
 *
 * <h2>Route discovery</h2>
 * Uses {@link RequestMappingHandlerMapping#getHandler(ServerWebExchange)} to resolve
 * the handler for the current request, then reads the {@link IdempotentRoute}
 * annotation from the {@link HandlerMethod}. This is the correct WebFlux mechanism
 * (not classpath scanning, not AOP).
 */
@Slf4j
@Component
@Order(SecurityProperties.DEFAULT_FILTER_ORDER + 2)
@RequiredArgsConstructor
public class IdempotencyWebFilter implements WebFilter {

    private static final String IDEMPOTENCY_KEY_HEADER = "Idempotency-Key";

    private static final Set<HttpMethod> MUTATION_METHODS = Set.of(
            HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH);

    private static final int ERROR_MISSING_KEY = 3100;
    private static final int ERROR_CONCURRENT_RACE = 3101;

    private final IdempotencyKeyRepository repository;
    private final RequestMappingHandlerMapping handlerMapping;

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        HttpMethod method = exchange.getRequest().getMethod();
        if (!MUTATION_METHODS.contains(method)) {
            return chain.filter(exchange);
        }

        // Resolve the handler to check for @IdempotentRoute.
        // getHandler() also writes HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE into
        // the exchange attributes, which we use to build the route key.
        return handlerMapping.getHandler(exchange)
                .cast(HandlerMethod.class)
                .flatMap(handlerMethod -> {
                    IdempotentRoute annotation = handlerMethod.getMethodAnnotation(IdempotentRoute.class);
                    if (annotation == null) {
                        // Not an idempotent route — pass through untouched
                        return chain.filter(exchange);
                    }
                    return handleIdempotentRequest(exchange, chain, method);
                })
                .switchIfEmpty(chain.filter(exchange)); // no handler found → let chain handle (404)
    }

    private Mono<Void> handleIdempotentRequest(
            ServerWebExchange exchange,
            WebFilterChain chain,
            HttpMethod method) {

        String idempotencyKeyValue = exchange.getRequest().getHeaders().getFirst(IDEMPOTENCY_KEY_HEADER);
        if (idempotencyKeyValue == null || idempotencyKeyValue.isBlank()) {
            return Mono.error(new DigiPresBeException(
                    "Idempotency-Key header is required for this endpoint",
                    ERROR_MISSING_KEY, HttpStatus.BAD_REQUEST.value()));
        }

        // Route key = METHOD:bestMatchingPattern (not raw URI — path vars must not fragment the key).
        // The pattern is set into exchange attributes by RequestMappingHandlerMapping.getHandler().
        String routeKey = buildRouteKey(method, exchange);

        return TenantContextHolder.required()
                .flatMap(tenantContext -> {
                    UUID tenantId = tenantContext.tenantId();

                    return repository.findByTenantIdAndRouteAndIdempotencyKey(
                                    tenantId, routeKey, idempotencyKeyValue)
                            .flatMap(existing -> replayResponse(exchange, existing))
                            .switchIfEmpty(Mono.defer(() ->
                                    runAndPersist(exchange, chain, tenantId, routeKey, idempotencyKeyValue)));
                });
    }

    /**
     * Replays a previously stored 2xx response without invoking the chain.
     */
    private Mono<Void> replayResponse(ServerWebExchange exchange, IdempotencyKey stored) {
        log.debug("Idempotency cache hit: route={}, status={}",
                stored.getRoute(), stored.getResponseStatus());

        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.valueOf(stored.getResponseStatus()));

        if (stored.getResponseContentType() != null) {
            response.getHeaders().setContentType(
                    MediaType.parseMediaType(stored.getResponseContentType()));
        }
        // Replay header to signal this is a cached response
        response.getHeaders().set("Idempotency-Replayed", "true");

        if (stored.getResponseBodyBase64() == null || stored.getResponseBodyBase64().isEmpty()) {
            return response.setComplete();
        }

        byte[] bodyBytes = Base64.getDecoder().decode(stored.getResponseBodyBase64());
        DataBuffer buffer = response.bufferFactory().wrap(bodyBytes);
        return response.writeWith(Mono.just(buffer));
    }

    /**
     * Runs the filter chain, tees the response body, and persists the result on 2xx.
     * The request body is never buffered — only the response is intercepted.
     */
    private Mono<Void> runAndPersist(
            ServerWebExchange exchange,
            WebFilterChain chain,
            UUID tenantId,
            String routeKey,
            String idempotencyKeyValue) {

        Sinks.One<byte[]> bodySink = Sinks.one();
        Sinks.One<String> contentTypeSink = Sinks.one();

        ServerHttpResponseDecorator decorator = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // Capture content type before first write
                MediaType ct = getDelegate().getHeaders().getContentType();
                contentTypeSink.tryEmitValue(ct != null ? ct.toString() : "application/json");

                return DataBufferUtils.join(Flux.from(body))
                        .flatMap(joined -> {
                            byte[] bytes = new byte[joined.readableByteCount()];
                            joined.read(bytes);
                            DataBufferUtils.release(joined);
                            bodySink.tryEmitValue(bytes);

                            DataBuffer copy = getDelegate().bufferFactory().wrap(bytes);
                            return getDelegate().writeWith(Mono.just(copy));
                        });
            }

            @Override
            public Mono<Void> writeAndFlushWith(Publisher<? extends Publisher<? extends DataBuffer>> body) {
                // For streaming responses, skip idempotency caching
                bodySink.tryEmitEmpty();
                contentTypeSink.tryEmitEmpty();
                return getDelegate().writeAndFlushWith(body);
            }
        };

        ServerWebExchange mutated = exchange.mutate().response(decorator).build();

        return chain.filter(mutated)
                .then(Mono.defer(() -> {
                    int status = exchange.getResponse().getStatusCode() != null
                            ? exchange.getResponse().getStatusCode().value()
                            : 200;

                    // Only persist 2xx responses
                    if (status < 200 || status >= 300) {
                        return Mono.empty();
                    }

                    return bodySink.asMono()
                            .defaultIfEmpty(new byte[0])
                            .zipWith(contentTypeSink.asMono().defaultIfEmpty("application/json"))
                            .flatMap(tuple -> {
                                byte[] body = tuple.getT1();
                                String contentType = tuple.getT2();
                                String bodyBase64 = body.length > 0
                                        ? Base64.getEncoder().encodeToString(body)
                                        : null;

                                IdempotencyKey record = IdempotencyKey.builder()
                                        .id(UUID.randomUUID())
                                        .tenantId(tenantId)
                                        .route(routeKey)
                                        .idempotencyKey(idempotencyKeyValue)
                                        .responseStatus(status)
                                        .responseBodyBase64(bodyBase64)
                                        .responseContentType(contentType)
                                        .createdAt(Instant.now())
                                        .build();

                                return repository.save(record)
                                        .doOnSuccess(saved -> log.debug(
                                                "Idempotency key persisted: route={}, status={}",
                                                routeKey, status))
                                        .onErrorMap(DuplicateKeyException.class, e ->
                                                new DigiPresBeException(
                                                        "Concurrent duplicate idempotency key detected",
                                                        ERROR_CONCURRENT_RACE,
                                                        HttpStatus.CONFLICT.value()))
                                        .then();
                            });
                }));
    }

    private String buildRouteKey(HttpMethod method, ServerWebExchange exchange) {
        // RequestMappingHandlerMapping.getHandler() sets BEST_MATCHING_PATTERN_ATTRIBUTE on the exchange.
        // This gives us the route template (e.g. "/communication/singleEmail") rather than the
        // raw URI path, so path variables don't fragment the idempotency key space.
        PathPattern pattern = exchange.getAttribute(
                org.springframework.web.reactive.HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String patternStr = pattern != null
                ? pattern.getPatternString()
                : exchange.getRequest().getPath().value();
        return method.name() + ":" + patternStr;
    }
}
