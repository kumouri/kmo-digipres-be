package com.kumouri.kmodigipresbe.automation;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

/**
 * Multicast event bus. Services publish via {@link #publish(DomainEvent)}; subscribers
 * (rule engine, webhook delivery) consume {@link #stream()}.
 *
 * <p>Backpressure: the sink is configured as {@code multicast().onBackpressureBuffer()}
 * with the default buffer size. Slow subscribers drop the oldest event after the buffer
 * is full and log a WARN — we don't want a hung subscriber to block the request path.
 */
@Slf4j
@Component
public class DomainEventPublisher {

    private final Sinks.Many<DomainEvent> sink =
            Sinks.many().multicast().onBackpressureBuffer();

    public void publish(DomainEvent event) {
        Sinks.EmitResult result = sink.tryEmitNext(event);
        if (result.isFailure()) {
            log.warn("Failed to emit domain event {}/{}: {}",
                    event.type(), event.tenantId(), result);
        }
    }

    public Flux<DomainEvent> stream() {
        return sink.asFlux();
    }
}
