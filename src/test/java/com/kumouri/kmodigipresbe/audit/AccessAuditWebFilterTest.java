package com.kumouri.kmodigipresbe.audit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link AccessAuditWebFilter} — delegates to the writer after the chain
 * completes, and skips excluded infra paths. No Spring context (runs in the fast CI gate).
 */
class AccessAuditWebFilterTest {

    private final AccessAuditEventWriter writer = mock(AccessAuditEventWriter.class);
    private final AccessAuditWebFilter filter = new AccessAuditWebFilter(writer);

    @Test
    void recordsAccessAfterChainCompletes() {
        when(writer.record(any())).thenReturn(Mono.empty());
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/contacts/" + UUID.randomUUID()));

        filter.filter(exchange, ex -> Mono.empty()).block();

        verify(writer).record(exchange);
    }

    @Test
    void skipsExcludedInfraPaths() {
        AtomicBoolean chainCalled = new AtomicBoolean(false);
        WebFilterChain chain = ex -> {
            chainCalled.set(true);
            return Mono.empty();
        };
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("/actuator/health"));

        filter.filter(exchange, chain).block();

        assertThat(chainCalled).isTrue();
        verify(writer, never()).record(any());
    }
}
