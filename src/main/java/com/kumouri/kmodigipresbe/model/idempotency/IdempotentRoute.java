package com.kumouri.kmodigipresbe.model.idempotency;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a controller method as requiring the {@code Idempotency-Key} request header.
 *
 * <p>When present, {@link com.kumouri.kmodigipresbe.config.IdempotencyWebFilter}
 * intercepts the request:
 * <ul>
 *   <li>If {@code Idempotency-Key} header is absent → {@code 400 Bad Request}
 *       with {@code errorCode=3100}.</li>
 *   <li>If the key has been seen before → replay the stored 2xx response
 *       without invoking the handler (side-effect-free replay).</li>
 *   <li>First call → run handler, tee the response, persist only on 2xx.</li>
 * </ul>
 *
 * <p><strong>Opt-in only:</strong> only annotate endpoints where idempotency has a
 * meaningful business definition (e.g. invoice payment, email send). Non-annotated
 * endpoints with an {@code Idempotency-Key} header are unaffected.
 *
 * <p>Route discovery uses {@link org.springframework.web.reactive.result.method.RequestMappingHandlerMapping}
 * rather than classpath scanning or AOP, so method-level annotation targeting is
 * sufficient and safe for WebFlux.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface IdempotentRoute {
}
